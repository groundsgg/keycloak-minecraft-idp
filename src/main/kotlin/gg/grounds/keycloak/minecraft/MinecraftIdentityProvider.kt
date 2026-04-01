package gg.grounds.keycloak.minecraft

import com.fasterxml.jackson.databind.JsonNode
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.XboxAuthApi
import java.io.IOException
import org.jboss.logging.Logger
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.events.EventBuilder
import org.keycloak.http.simple.SimpleHttpRequest
import org.keycloak.models.KeycloakSession

/**
 * Identity Provider that authenticates users via Microsoft/Xbox OAuth2 and retrieves their
 * Minecraft Java Edition username or Xbox Gamertag (Bedrock).
 */
class MinecraftIdentityProvider(session: KeycloakSession, config: MinecraftIdentityProviderConfig) :
    AbstractOAuth2IdentityProvider<MinecraftIdentityProviderConfig>(session, config) {

    private val xboxAuthApi = XboxAuthApi()
    private val minecraftApi = MinecraftApi()

    override fun getDefaultScopes(): String = config.defaultScope

    override fun supportsExternalExchange(): Boolean = false

    /**
     * Fix #8/#12: Null-safe fallback — only used if Keycloak routes through this path. Not part of
     * the main auth flow (doGetFederatedIdentity handles everything).
     */
    override fun extractIdentityFromProfile(
        event: EventBuilder?,
        profile: JsonNode,
    ): BrokeredIdentityContext {
        val id =
            profile.get("id")?.asText()
                ?: throw IdentityBrokerException("Profile response missing 'id' field")
        val username =
            profile.get("name")?.asText()
                ?: throw IdentityBrokerException("Profile response missing 'name' field")

        return BrokeredIdentityContext(id, config).apply { this.username = username }
    }

    override fun doGetFederatedIdentity(accessToken: String): BrokeredIdentityContext {
        try {
            // Step 1: Authenticate with Xbox Live
            val xboxResponse = xboxAuthApi.authenticateWithXbox(accessToken)
            // Step 2: Obtain XSTS token scoped to Minecraft services
            val xstsResponse =
                try {
                    xboxAuthApi.obtainXstsToken(xboxResponse.token)
                } catch (e: XboxAuthApi.XboxAuthException) {
                    logger.warnf(
                        e,
                        "Requested XSTS token failed (provider=%s, reason=%s)",
                        PROVIDER_ID,
                        e.message ?: e.javaClass.simpleName,
                    )
                    throw IdentityBrokerException(e.message, e)
                }

            val xstsToken = xstsResponse.token
            val xboxGamertag = xstsResponse.gamertag
            val xboxUserId = xstsResponse.xboxUserId
            // Fix #5: userHash not logged (PII). Fix #2 (userHash-source): use uhs from XSTS
            // response per spec — it's the same value as from the Xbox Live step, but
            // spec-compliant.
            val userHash =
                xstsResponse.userHash
                    ?: throw IdentityBrokerException("XSTS response did not return a user hash")

            // Step 3: Authenticate with Minecraft services
            val mcAuthResponse = minecraftApi.authenticateWithMinecraft(userHash, xstsToken)
            val minecraftToken = mcAuthResponse.accessToken

            // Step 4: Resolve edition ownership via entitlement's endpoint
            val ownership = minecraftApi.getOwnership(minecraftToken)

            if (ownership.ownsJavaEdition) {
                return try {
                    // Step 5a: Fetch Java Edition profile
                    val profile = minecraftApi.getProfile(minecraftToken)
                    logger.info(
                        "Resolved Minecraft identity successfully (provider=$PROVIDER_ID, edition=java)"
                    )
                    buildJavaIdentity(profile, xboxGamertag, xboxUserId, ownership)
                } catch (e: MinecraftApi.MinecraftProfileNotFoundException) {
                    if (ownership.ownsBedrockEdition) {
                        logger.warnf(
                            e,
                            "Resolved Minecraft Java profile missing; falling back to Bedrock identity (provider=%s)",
                            PROVIDER_ID,
                        )
                        buildBedrockIdentity(xboxGamertag, xboxUserId, ownership)
                    } else {
                        // Game Pass user who has never opened the launcher — profile setup required
                        logger.warnf(
                            e,
                            "Resolved Minecraft identity failed (provider=%s, edition=java, reason=%s)",
                            PROVIDER_ID,
                            e.message ?: e.javaClass.simpleName,
                        )
                        throw IdentityBrokerException(
                            "Your account has Minecraft Java Edition but no Java profile exists yet. " +
                                "Please log into the Minecraft Launcher once to set up your profile."
                        )
                    }
                }
            } else if (ownership.ownsBedrockEdition) {
                return buildBedrockIdentity(xboxGamertag, xboxUserId, ownership)
            } else {
                throw IdentityBrokerException(
                    "This Microsoft account does not have a Minecraft Java or Bedrock entitlement."
                )
            }
        } catch (e: XboxAuthApi.XboxAuthException) {
            logger.warnf(
                e,
                "Authenticated with Xbox failed (provider=%s, reason=%s)",
                PROVIDER_ID,
                e.message ?: e.javaClass.simpleName,
            )
            throw IdentityBrokerException(e.message, e)
        } catch (e: IOException) {
            logger.errorf(
                e,
                "Authenticated with Minecraft services failed (provider=%s, reason=%s)",
                PROVIDER_ID,
                e.message ?: e.javaClass.simpleName,
            )
            throw IdentityBrokerException("Minecraft authentication failed. Please try again.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warnf(
                e,
                "Authenticated with Minecraft services interrupted (provider=%s, reason=request_interrupted)",
                PROVIDER_ID,
            )
            throw IdentityBrokerException("Minecraft authentication was interrupted.", e)
        }
    }

    private fun buildJavaIdentity(
        profile: MinecraftApi.MinecraftProfile,
        xboxGamertag: String?,
        xboxUserId: String?,
        ownership: MinecraftApi.Ownership,
    ): BrokeredIdentityContext =
        BrokeredIdentityContext(profile.formattedUuid, config).apply {
            username = profile.name
            brokerUserId = profile.formattedUuid
            setOwnershipAttributes(ownership)
            setUserAttribute("minecraft_login_identity", "java")
            setUserAttribute("minecraft_java_uuid", profile.id)
            setUserAttribute("minecraft_java_username", profile.name)
            xboxGamertag?.let { setUserAttribute("xbox_gamertag", it) }
            xboxUserId?.let { setUserAttribute("xbox_user_id", it) }
        }

    private fun buildBedrockIdentity(
        xboxGamertag: String?,
        xboxUserId: String?,
        ownership: MinecraftApi.Ownership,
    ): BrokeredIdentityContext {
        if (xboxGamertag.isNullOrBlank()) {
            throw IdentityBrokerException("Could not retrieve Xbox Gamertag for Bedrock user")
        }
        // Fix #2: throw instead of hashCode() — xboxUserId must be present for a stable identity
        val uniqueId =
            xboxUserId?.let { "xbox-$it" }
                ?: throw IdentityBrokerException(
                    "Could not retrieve a stable Xbox User ID for Bedrock user"
                )

        logger.info(
            "Resolved Minecraft identity successfully (provider=$PROVIDER_ID, edition=bedrock)"
        )

        return BrokeredIdentityContext(uniqueId, config).apply {
            username = xboxGamertag
            brokerUserId = uniqueId
            setOwnershipAttributes(ownership)
            setUserAttribute("minecraft_login_identity", "bedrock")
            setUserAttribute("xbox_gamertag", xboxGamertag)
            setUserAttribute("xbox_user_id", xboxUserId)
        }
    }

    private fun BrokeredIdentityContext.setOwnershipAttributes(ownership: MinecraftApi.Ownership) {
        setUserAttribute("minecraft_java_owned", ownership.ownsJavaEdition.toString())
        setUserAttribute("minecraft_bedrock_owned", ownership.ownsBedrockEdition.toString())
    }

    override fun getProfileEndpointForValidation(event: EventBuilder?): String? = null

    /** Use POST body for client credentials — Microsoft does not support HTTP Basic Auth here. */
    override fun authenticateTokenRequest(tokenRequest: SimpleHttpRequest): SimpleHttpRequest =
        tokenRequest.param("client_id", config.clientId).param("client_secret", config.clientSecret)

    companion object {
        const val PROVIDER_ID = "minecraft"
        private val logger = Logger.getLogger(MinecraftIdentityProvider::class.java)
    }
}
