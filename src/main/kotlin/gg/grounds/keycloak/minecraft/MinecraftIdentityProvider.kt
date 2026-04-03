package gg.grounds.keycloak.minecraft

import com.fasterxml.jackson.databind.JsonNode
import gg.grounds.keycloak.minecraft.identity.MinecraftIdentityContextFactory
import gg.grounds.keycloak.minecraft.identity.MinecraftIdentityResolver
import gg.grounds.keycloak.minecraft.sync.MinecraftBrokeredUserSynchronizer
import java.io.IOException
import org.jboss.logging.Logger
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.events.EventBuilder
import org.keycloak.http.simple.SimpleHttpRequest
import org.keycloak.jose.jws.JWSInput
import org.keycloak.jose.jws.JWSInputException
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
    private val identityResolver: MinecraftIdentityResolver = MinecraftIdentityResolver(),
    private val identityContextFactory: MinecraftIdentityContextFactory =
        MinecraftIdentityContextFactory(config),
    private val userSynchronizer: MinecraftBrokeredUserSynchronizer =
        MinecraftBrokeredUserSynchronizer(config),
) : AbstractOAuth2IdentityProvider<MinecraftIdentityProviderConfig>(session, config) {

    constructor(
        session: KeycloakSession,
        config: MinecraftIdentityProviderConfig,
        xboxAuthClient: gg.grounds.keycloak.minecraft.api.XboxAuthClient,
        minecraftClient: gg.grounds.keycloak.minecraft.api.MinecraftClient,
    ) : this(
        session,
        config,
        identityResolver = MinecraftIdentityResolver(xboxAuthClient, minecraftClient),
        identityContextFactory = MinecraftIdentityContextFactory(config),
        userSynchronizer = MinecraftBrokeredUserSynchronizer(config),
    )

    override fun getDefaultScopes(): String = config.defaultScope

    override fun supportsExternalExchange(): Boolean = false

    override fun updateBrokeredUser(
        session: KeycloakSession,
        realm: RealmModel,
        user: UserModel,
        context: BrokeredIdentityContext,
    ) {
        super.updateBrokeredUser(session, realm, user, context)
        userSynchronizer.sync(user, context)
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
        return identityContextFactory.create(identityResolver.resolve(accessToken))
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
        config.requireSupportedClientAuthMethodForBroker()
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
                val resolvedSecret = vaultSecret.get()
                if (resolvedSecret.filter { it.isNotBlank() }.isEmpty) {
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
