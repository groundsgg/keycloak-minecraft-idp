package gg.grounds.keycloak.minecraft

import com.fasterxml.jackson.databind.JsonNode
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.MinecraftClient
import gg.grounds.keycloak.minecraft.api.XboxAuthApi
import gg.grounds.keycloak.minecraft.api.XboxAuthClient
import java.io.IOException
import org.jboss.logging.Logger
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.events.EventBuilder
import org.keycloak.http.simple.SimpleHttpRequest
import org.keycloak.jose.jws.JWSInput
import org.keycloak.jose.jws.JWSInputException
import org.keycloak.models.IdentityProviderSyncMode
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.util.JsonSerialization
import org.keycloak.vault.VaultStringSecret

/**
 * Identity Provider that authenticates users via Microsoft/Xbox OAuth2 and retrieves their
 * Minecraft Java Edition username or Xbox Gamertag (Bedrock).
 */
class MinecraftIdentityProvider(
    session: KeycloakSession,
    config: MinecraftIdentityProviderConfig,
    private val xboxAuthClient: XboxAuthClient = XboxAuthApi(),
    private val minecraftClient: MinecraftClient = MinecraftApi(),
) : AbstractOAuth2IdentityProvider<MinecraftIdentityProviderConfig>(session, config) {

    override fun getDefaultScopes(): String = config.defaultScope

    override fun supportsExternalExchange(): Boolean = false

    override fun updateBrokeredUser(
        session: KeycloakSession,
        realm: RealmModel,
        user: UserModel,
        context: BrokeredIdentityContext,
    ) {
        super.updateBrokeredUser(session, realm, user, context)
        syncBasicProfile(user, context)
    }

    override fun getFederatedIdentity(response: String): BrokeredIdentityContext {
        val context = super.getFederatedIdentity(response)
        extractMicrosoftIdTokenClaims(response)?.let { context.applyMicrosoftProfile(it) }
        return context
    }

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
            val xboxResponse = xboxAuthClient.authenticateWithXbox(accessToken)
            // Step 2: Obtain XSTS token scoped to Minecraft services
            val xstsResponse =
                try {
                    xboxAuthClient.obtainXstsToken(xboxResponse.token)
                } catch (e: XboxAuthApi.XboxAuthException) {
                    logger.warnf(
                        e,
                        "Requested XSTS token failed (provider=%s, xerr=%d, reason=%s, rawMessage=%s, redirect=%s)",
                        PROVIDER_ID,
                        e.errorCode,
                        e.message ?: e.javaClass.simpleName,
                        e.rawMessage,
                        e.redirectUrl,
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
            val stableBrokerUserId = resolveStableBrokerUserId(xboxUserId, userHash)

            // Step 3: Authenticate with Minecraft services
            val mcAuthResponse = minecraftClient.authenticateWithMinecraft(userHash, xstsToken)
            val minecraftToken = mcAuthResponse.accessToken

            // Step 4: Resolve edition ownership via entitlement's endpoint
            val ownership = minecraftClient.getOwnership(minecraftToken)

            if (ownership.ownsJavaEdition) {
                return try {
                    // Step 5a: Fetch Java Edition profile
                    val profile = minecraftClient.getProfile(minecraftToken)
                    logger.info(
                        "Resolved Minecraft identity successfully (provider=$PROVIDER_ID, edition=java)"
                    )
                    buildJavaIdentity(
                        profile,
                        stableBrokerUserId,
                        xboxGamertag,
                        xboxUserId,
                        ownership,
                    )
                } catch (e: MinecraftApi.MinecraftProfileNotFoundException) {
                    if (ownership.ownsBedrockEdition) {
                        logger.warnf(
                            e,
                            "Resolved Minecraft Java profile missing; falling back to Bedrock identity (provider=%s)",
                            PROVIDER_ID,
                        )
                        buildBedrockIdentity(
                            stableBrokerUserId,
                            xboxGamertag,
                            xboxUserId,
                            ownership,
                        )
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
                return buildBedrockIdentity(stableBrokerUserId, xboxGamertag, xboxUserId, ownership)
            } else {
                throw IdentityBrokerException(
                    "This Microsoft account does not have a Minecraft Java or Bedrock entitlement."
                )
            }
        } catch (e: XboxAuthApi.XboxAuthException) {
            logger.warnf(
                e,
                "Authenticated with Xbox failed (provider=%s, xerr=%d, reason=%s, rawMessage=%s, redirect=%s)",
                PROVIDER_ID,
                e.errorCode,
                e.message ?: e.javaClass.simpleName,
                e.rawMessage,
                e.redirectUrl,
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
        brokerUserId: String,
        xboxGamertag: String?,
        xboxUserId: String?,
        ownership: MinecraftApi.Ownership,
    ): BrokeredIdentityContext =
        BrokeredIdentityContext(brokerUserId, config).apply {
            username = profile.name
            this.brokerUserId = brokerUserId
            setOwnershipAttributes(ownership)
            setUserAttribute("minecraft_login_identity", "java")
            setUserAttribute("minecraft_java_uuid", profile.formattedUuid)
            setUserAttribute("minecraft_java_username", profile.name)
            xboxGamertag?.let { setUserAttribute("xbox_gamertag", it) }
            xboxUserId?.let { setUserAttribute("xbox_user_id", it) }
        }

    private fun buildBedrockIdentity(
        brokerUserId: String,
        xboxGamertag: String?,
        xboxUserId: String?,
        ownership: MinecraftApi.Ownership,
    ): BrokeredIdentityContext {
        if (xboxGamertag.isNullOrBlank()) {
            throw IdentityBrokerException("Could not retrieve Xbox Gamertag for Bedrock user")
        }

        logger.info(
            "Resolved Minecraft identity successfully (provider=$PROVIDER_ID, edition=bedrock)"
        )

        return BrokeredIdentityContext(brokerUserId, config).apply {
            username = xboxGamertag
            this.brokerUserId = brokerUserId
            setOwnershipAttributes(ownership)
            setUserAttribute("minecraft_login_identity", "bedrock")
            setUserAttribute("xbox_gamertag", xboxGamertag)
            xboxUserId?.let { setUserAttribute("xbox_user_id", it) }
        }
    }

    private fun resolveStableBrokerUserId(xboxUserId: String?, userHash: String): String =
        xboxUserId?.let { "xbox-$it" } ?: "xboxuhs-$userHash"

    private fun BrokeredIdentityContext.setOwnershipAttributes(ownership: MinecraftApi.Ownership) {
        setUserAttribute("minecraft_java_owned", ownership.ownsJavaEdition.toString())
        setUserAttribute("minecraft_bedrock_owned", ownership.ownsBedrockEdition.toString())
    }

    private fun syncBasicProfile(user: UserModel, context: BrokeredIdentityContext) {
        if (!config.syncRealName) {
            return
        }

        val authSession = context.authenticationSession ?: return
        val isNewUser =
            authSession.getAuthNote(BROKER_REGISTERED_NEW_USER).toBoolean()
        val shouldSync = isNewUser || config.syncMode == IdentityProviderSyncMode.FORCE

        if (!shouldSync) {
            return
        }

        context.firstName?.takeIf { it != user.firstName }?.let { user.firstName = it }
        context.lastName?.takeIf { it != user.lastName }?.let { user.lastName = it }
    }

    private fun BrokeredIdentityContext.applyMicrosoftProfile(idTokenClaims: JsonNode) {
        idTokenClaims.getClaimText("email")?.let { email = it }
        if (!config.syncRealName) {
            return
        }
        idTokenClaims.getClaimText("given_name")?.let { firstName = it }
        idTokenClaims.getClaimText("family_name")?.let { lastName = it }
    }

    override fun getProfileEndpointForValidation(event: EventBuilder?): String? = null

    /** Use Keycloak's standard token-request handling after enforcing Microsoft's auth mode. */
    override fun authenticateTokenRequest(tokenRequest: SimpleHttpRequest): SimpleHttpRequest {
        config.requireSupportedClientAuthMethod()
        requireConfiguredCredential("clientId", config.clientId)
        val clientSecret = requireConfiguredCredential("clientSecret", config.clientSecret)
        requireResolvedVaultSecret("clientSecret", clientSecret)
        return super.authenticateTokenRequest(tokenRequest)
    }

    private fun requireConfiguredCredential(name: String, value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: throw IdentityBrokerException(
                "Minecraft identity provider is missing `$name`. " +
                    "Configure it in the Admin UI or via SPI " +
                    "(KC_SPI_IDENTITY_PROVIDER_MINECRAFT_${name.toScreamingSnakeCase()})."
            )

    private fun requireResolvedVaultSecret(name: String, value: String) {
        if (!value.startsWith(VAULT_REFERENCE_PREFIX)) {
            return
        }

        try {
            session.vault().getStringSecret(value).use { vaultSecret: VaultStringSecret ->
                if (vaultSecret.get().isEmpty) {
                    throw IdentityBrokerException(
                        "Minecraft identity provider could not resolve `$name` from Keycloak vault."
                    )
                }
            }
        } catch (e: IdentityBrokerException) {
            throw e
        } catch (e: RuntimeException) {
            throw IdentityBrokerException(
                "Minecraft identity provider could not resolve `$name` from Keycloak vault.",
                e,
            )
        }
    }

    private fun extractMicrosoftIdTokenClaims(response: String): JsonNode? =
        try {
            val tokenResponse = JsonSerialization.readValue(response, OAuthResponse::class.java)
            val rawIdToken = tokenResponse.idToken ?: return null
            JsonSerialization.mapper.readTree(JWSInput(rawIdToken).content)
        } catch (e: IOException) {
            logger.debugf(
                e,
                "Skipped Microsoft ID token extraction (provider=%s, reason=%s)",
                PROVIDER_ID,
                e.message ?: e.javaClass.simpleName,
            )
            null
        } catch (e: JWSInputException) {
            logger.warnf(
                e,
                "Failed to parse Microsoft ID token (provider=%s, reason=%s)",
                PROVIDER_ID,
                e.message ?: e.javaClass.simpleName,
            )
            null
        }

    private fun JsonNode.getClaimText(name: String): String? =
        get(name)?.asText()?.takeIf { it.isNotBlank() }

    private fun String.toScreamingSnakeCase(): String =
        replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    companion object {
        const val PROVIDER_ID = "minecraft"
        private const val VAULT_REFERENCE_PREFIX = "vault:"
        private val logger = Logger.getLogger(MinecraftIdentityProvider::class.java)
    }
}
