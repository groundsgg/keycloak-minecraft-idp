package gg.grounds.keycloak.minecraft

import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.XboxAuthApi
import org.jboss.logging.Logger
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.events.EventBuilder
import org.keycloak.http.simple.SimpleHttpRequest
import org.keycloak.models.KeycloakSession
import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException

/**
 * Identity Provider that authenticates users via Microsoft/Xbox OAuth2
 * and retrieves their Minecraft Java Edition username or Xbox Gamertag (Bedrock).
 */
class MinecraftIdentityProvider(
    session: KeycloakSession,
    config: MinecraftIdentityProviderConfig
) : AbstractOAuth2IdentityProvider<MinecraftIdentityProviderConfig>(session, config) {

    private val xboxAuthApi = XboxAuthApi()
    private val minecraftApi = MinecraftApi()

    override fun getDefaultScopes(): String = config.defaultScope

    override fun supportsExternalExchange(): Boolean = true

    /**
     * Fix #8/#12: Null-safe fallback — only used if Keycloak routes through this path.
     * Not part of the main auth flow (doGetFederatedIdentity handles everything).
     */
    override fun extractIdentityFromProfile(event: EventBuilder?, profile: JsonNode): BrokeredIdentityContext {
        val id = profile.get("id")?.asText()
            ?: throw IdentityBrokerException("Profile response missing 'id' field")
        val username = profile.get("name")?.asText()
            ?: throw IdentityBrokerException("Profile response missing 'name' field")

        return BrokeredIdentityContext(id, config).apply {
            this.username = username
        }
    }

    override fun doGetFederatedIdentity(accessToken: String): BrokeredIdentityContext {
        try {
            // Fix #9: debug instead of info — logged on every login attempt
            logger.debug("Starting Minecraft authentication flow")

            // Step 1: Authenticate with Xbox Live
            val xboxResponse = xboxAuthApi.authenticateWithXbox(accessToken)
            // Step 2: Obtain XSTS token scoped to Minecraft services
            val xstsResponse = try {
                xboxAuthApi.obtainXstsToken(xboxResponse.token)
            } catch (e: XboxAuthApi.XboxAuthException) {
                logger.warnf("Xbox XSTS authentication failed: %s", e.message)
                throw IdentityBrokerException(e.message, e)
            }

            val xstsToken = xstsResponse.token
            val xboxGamertag = xstsResponse.gamertag
            val xboxUserId = xstsResponse.xboxUserId
            logger.debug("XSTS token obtained successfully")

            // Fix #5: userHash not logged (PII). Fix #2 (userHash-source): use uhs from XSTS
            // response per spec — it's the same value as from Xbox Live step, but spec-compliant.
            val userHash = xstsResponse.userHash
                ?: throw IdentityBrokerException("XSTS response did not return a user hash")

            // Step 3: Authenticate with Minecraft services
            val mcAuthResponse = minecraftApi.authenticateWithMinecraft(userHash, xstsToken)
            val minecraftToken = mcAuthResponse.accessToken

            // Step 4: Check Java Edition ownership via entitlements endpoint
            // Fix #1: proper ownership check — avoids misclassifying Game Pass users as Bedrock
            val ownsJavaEdition = minecraftApi.checkOwnership(minecraftToken)

            if (ownsJavaEdition) {
                return try {
                    // Step 5a: Fetch Java Edition profile
                    val profile = minecraftApi.getProfile(minecraftToken)
                    logger.infof(
                        "Minecraft Java Edition profile: %s (UUID: %s)",
                        profile.name, profile.formattedUuid
                    )
                    buildJavaIdentity(profile, xboxGamertag, xboxUserId)
                } catch (e: MinecraftApi.MinecraftProfileNotFoundException) {
                    // Game Pass user who has never opened the launcher — profile setup required
                    logger.warnf(
                        "User owns Java Edition (Game Pass) but has no profile yet: %s", e.message
                    )
                    throw IdentityBrokerException(
                        "Your account has Minecraft via Game Pass but no profile exists yet. " +
                            "Please log into the Minecraft Launcher once to set up your profile."
                    )
                }
            } else {
                // Step 5b: Bedrock Edition fallback — use Xbox identity
                return buildBedrockIdentity(xboxGamertag, xboxUserId)
            }
        } catch (e: XboxAuthApi.XboxAuthException) {
            logger.warnf("Xbox authentication failed: %s", e.message)
            throw IdentityBrokerException(e.message, e)
        } catch (e: IOException) {
            logger.error("Failed to authenticate with Minecraft services", e)
            throw IdentityBrokerException("Minecraft authentication failed. Please try again.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IdentityBrokerException("Minecraft authentication was interrupted.", e)
        }
    }

    private fun buildJavaIdentity(
        profile: MinecraftApi.MinecraftProfile,
        xboxGamertag: String?,
        xboxUserId: String?
    ): BrokeredIdentityContext = BrokeredIdentityContext(profile.formattedUuid, config).apply {
        username = profile.name
        brokerUserId = profile.formattedUuid
        setUserAttribute("minecraft_uuid", profile.id)
        setUserAttribute("minecraft_username", profile.name)
        setUserAttribute("minecraft_edition", "java")
        xboxGamertag?.let { setUserAttribute("xbox_gamertag", it) }
        xboxUserId?.let { setUserAttribute("xbox_user_id", it) }
    }

    private fun buildBedrockIdentity(
        xboxGamertag: String?,
        xboxUserId: String?
    ): BrokeredIdentityContext {
        if (xboxGamertag.isNullOrBlank()) {
            throw IdentityBrokerException("Could not retrieve Xbox Gamertag for Bedrock user")
        }
        // Fix #2: throw instead of hashCode() — xboxUserId must be present for a stable identity
        val uniqueId = xboxUserId?.let { "xbox-$it" }
            ?: throw IdentityBrokerException(
                "Could not retrieve a stable Xbox User ID for Bedrock user"
            )

        logger.infof("Bedrock Edition user: %s", xboxGamertag)

        return BrokeredIdentityContext(uniqueId, config).apply {
            username = xboxGamertag
            brokerUserId = uniqueId
            setUserAttribute("minecraft_username", xboxGamertag)
            setUserAttribute("minecraft_edition", "bedrock")
            setUserAttribute("xbox_gamertag", xboxGamertag)
            setUserAttribute("xbox_user_id", xboxUserId)
        }
    }

    override fun getProfileEndpointForValidation(event: EventBuilder?): String? = null

    /** Use POST body for client credentials — Microsoft does not support HTTP Basic Auth here. */
    override fun authenticateTokenRequest(tokenRequest: SimpleHttpRequest): SimpleHttpRequest =
        tokenRequest
            .param("client_id", config.clientId)
            .param("client_secret", config.clientSecret)

    companion object {
        const val PROVIDER_ID = "minecraft"
        private val logger = Logger.getLogger(MinecraftIdentityProvider::class.java)
    }
}
