package gg.grounds.keycloak.minecraft

import com.fasterxml.jackson.databind.JsonNode
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.identity.MinecraftIdentityContextFactory
import gg.grounds.keycloak.minecraft.identity.MinecraftIdentityResolver
import gg.grounds.keycloak.minecraft.identity.MinecraftServicesDegradedException
import gg.grounds.keycloak.minecraft.identity.PartnerXstsTokenInspector
import gg.grounds.keycloak.minecraft.identity.ResolvedMinecraftIdentity
import gg.grounds.keycloak.minecraft.sync.MinecraftBrokeredAttributes
import gg.grounds.keycloak.minecraft.sync.MinecraftBrokeredUserSynchronizer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jboss.logging.Logger
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.broker.social.SocialIdentityProvider
import org.keycloak.events.EventBuilder
import org.keycloak.http.simple.SimpleHttpRequest
import org.keycloak.jose.jws.JWSInput
import org.keycloak.jose.jws.JWSInputException
import org.keycloak.models.FederatedIdentityModel
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
    private val identityResolver: MinecraftIdentityResolver =
        MinecraftIdentityResolver(
            partnerRelyingParty = config.requirePartnerRelyingPartyForBroker(),
            partnerTokenInspector = createPartnerTokenInspector(session, config),
        ),
    private val identityContextFactory: MinecraftIdentityContextFactory =
        MinecraftIdentityContextFactory(config),
    private val userSynchronizer: MinecraftBrokeredUserSynchronizer =
        MinecraftBrokeredUserSynchronizer(config),
) :
    AbstractOAuth2IdentityProvider<MinecraftIdentityProviderConfig>(session, config),
    SocialIdentityProvider<MinecraftIdentityProviderConfig> {

    constructor(
        session: KeycloakSession,
        config: MinecraftIdentityProviderConfig,
        xboxAuthClient: gg.grounds.keycloak.minecraft.api.XboxAuthClient,
        minecraftClient: gg.grounds.keycloak.minecraft.api.MinecraftClient,
    ) : this(
        session,
        config,
        identityResolver =
            MinecraftIdentityResolver(
                xboxAuthClient,
                minecraftClient,
                config.requirePartnerRelyingPartyForBroker(),
                createPartnerTokenInspector(session, config),
            ),
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
        return try {
            identityContextFactory.create(identityResolver.resolve(accessToken))
        } catch (e: MinecraftServicesDegradedException) {
            degradedFederatedIdentity(e.stableBrokerUserId, e)
        }
    }

    /**
     * Fallback used when Minecraft Services is down (5xx) but the user's stable broker id was
     * already resolved from the validated partner XSTS token.
     *
     * For a **returning** user we already have a federated link (keyed on that broker id) plus the
     * Minecraft attributes we stored on the last successful login — so we rebuild an equivalent
     * brokered context from those and let them in without touching Minecraft Services. Rebuilding
     * the full identity (rather than a bare context) matters: the synchronizer removes managed
     * attributes that are absent from the context, so a partial context would wipe the stored
     * Minecraft data.
     *
     * A **first-time** login can't be completed offline (ownership/UUID are only resolvable via
     * Minecraft Services), so with no existing user we fail cleanly with a retry message.
     */
    private fun degradedFederatedIdentity(
        brokerUserId: String,
        cause: Exception,
    ): BrokeredIdentityContext {
        val realm = session.context.realm
        val existing =
            session
                .users()
                .getUserByFederatedIdentity(
                    realm,
                    FederatedIdentityModel(config.alias, brokerUserId, brokerUserId),
                )
                ?: run {
                    logger.warnf(
                        cause,
                        "Minecraft services unavailable and no existing federated user for brokerUserId=%s (provider=%s) — a first login cannot be verified offline, failing",
                        brokerUserId,
                        PROVIDER_ID,
                    )
                    throw IdentityBrokerException(
                        "Minecraft login is temporarily unavailable. Please try again shortly.",
                        cause,
                    )
                }
        logger.warnf(
            "Minecraft services unavailable (provider=%s) — degraded login for returning user %s (brokerUserId=%s) using stored attributes",
            PROVIDER_ID,
            existing.username,
            brokerUserId,
        )
        return identityContextFactory.create(rebuildIdentityFrom(brokerUserId, existing))
    }

    /** Reconstructs the resolved identity from the attributes stored on a returning user. */
    private fun rebuildIdentityFrom(
        brokerUserId: String,
        user: UserModel,
    ): ResolvedMinecraftIdentity {
        val javaUsername = user.getFirstAttribute(MinecraftBrokeredAttributes.JAVA_USERNAME)
        val gamertag = user.getFirstAttribute(MinecraftBrokeredAttributes.XBOX_GAMERTAG)
        return ResolvedMinecraftIdentity(
            brokerUserId = brokerUserId,
            username = javaUsername ?: gamertag ?: user.username,
            loginIdentity =
                user.getFirstAttribute(MinecraftBrokeredAttributes.LOGIN_IDENTITY) ?: brokerUserId,
            ownership =
                MinecraftApi.Ownership(
                    // Entitlement names aren't persisted (only the owned flags are); the context
                    // factory uses the flags, not this set, so an empty set is correct here.
                    entitlementNames = emptySet(),
                    ownsJavaEdition =
                        user.getFirstAttribute(MinecraftBrokeredAttributes.JAVA_OWNED)?.toBoolean()
                            ?: false,
                    ownsBedrockEdition =
                        user
                            .getFirstAttribute(MinecraftBrokeredAttributes.BEDROCK_OWNED)
                            ?.toBoolean() ?: false,
                ),
            minecraftJavaUuid = user.getFirstAttribute(MinecraftBrokeredAttributes.JAVA_UUID),
            minecraftJavaUsername = javaUsername,
            xboxGamertag = gamertag,
        )
    }

    private fun BrokeredIdentityContext.applyMicrosoftProfile(idTokenClaims: JsonNode) {
        val emailClaim = idTokenClaims.getClaimText("email")
        val nameClaim = idTokenClaims.getClaimText("name")
        val givenNameClaim = idTokenClaims.getClaimText("given_name")
        val familyNameClaim = idTokenClaims.getClaimText("family_name")

        logger.debugf(
            "Resolved Microsoft profile claims (provider=%s, emailPresent=%s, namePresent=%s, givenNamePresent=%s, familyNamePresent=%s, syncRealNameEnabled=%s)",
            PROVIDER_ID,
            emailClaim != null,
            nameClaim != null,
            givenNameClaim != null,
            familyNameClaim != null,
            config.syncRealName,
        )

        emailClaim?.let { email = it }
        if (!config.syncRealName) {
            return
        }
        nameClaim?.let { setUserAttribute(MinecraftBrokeredAttributes.MICROSOFT_NAME, it) }
        givenNameClaim?.let { firstName = it }
        familyNameClaim?.let { lastName = it }
    }

    override fun getProfileEndpointForValidation(event: EventBuilder?): String? = null

    /** Use Keycloak's standard token-request handling after enforcing Microsoft's auth mode. */
    override fun authenticateTokenRequest(tokenRequest: SimpleHttpRequest): SimpleHttpRequest {
        config.requireSupportedClientAuthMethodForBroker()
        config.requirePartnerRelyingPartyForBroker()
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
            val rawIdToken =
                tokenResponse.idToken
                    ?: run {
                        logger.debugf(
                            "Skipped Microsoft ID token extraction (provider=%s, reason=%s)",
                            PROVIDER_ID,
                            "id_token_missing",
                        )
                        return null
                    }
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

        private fun createPartnerTokenInspector(
            session: KeycloakSession,
            config: MinecraftIdentityProviderConfig,
        ): PartnerXstsTokenInspector =
            try {
                PartnerXstsTokenInspector.fromPemReference(
                    resolveOptionalConfiguredSecret(
                        session,
                        "partnerXstsPrivateKey",
                        config.partnerXstsPrivateKey,
                    )
                )
            } catch (e: IdentityBrokerException) {
                throw e
            } catch (e: Exception) {
                throw IdentityBrokerException(
                    "Minecraft identity provider could not load `partnerXstsPrivateKey`.",
                    e,
                )
            }

        private fun resolveOptionalConfiguredSecret(
            session: KeycloakSession,
            name: String,
            value: String?,
        ): String? {
            val trimmedValue = value?.takeIf { it.isNotBlank() } ?: return null
            if (trimmedValue.startsWith(VAULT_REFERENCE_PREFIX)) {
                return resolveVaultSecret(session, name, trimmedValue)
            }

            return when {
                trimmedValue.startsWith("file:") ->
                    Files.readString(Paths.get(java.net.URI.create(trimmedValue)))
                trimmedValue.startsWith("/") ||
                    trimmedValue.startsWith("./") ||
                    trimmedValue.startsWith("../") -> Files.readString(Path.of(trimmedValue))
                else -> trimmedValue
            }
        }

        private fun resolveVaultSecret(
            session: KeycloakSession,
            name: String,
            value: String,
        ): String {
            try {
                session.vault().getStringSecret(value).use { vaultSecret: VaultStringSecret ->
                    val resolvedSecret =
                        vaultSecret
                            .get()
                            .filter { it.isNotBlank() }
                            .orElseThrow {
                                IdentityBrokerException(
                                    "Minecraft identity provider could not resolve `$name` from Keycloak vault."
                                )
                            }
                    return resolvedSecret
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
    }
}
