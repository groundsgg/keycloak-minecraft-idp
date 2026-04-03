package gg.grounds.keycloak.minecraft

import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.RealmModel

/**
 * Configuration for the Minecraft Identity Provider.
 *
 * Client credentials are resolved in this priority order:
 * 1. Admin UI / realm database
 * 2. SPI configuration (env vars or kc.sh flags): KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_ID
 *    KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_SECRET or equivalently:
 *    --spi-identity-provider-minecraft-client-id=<value>
 *    --spi-identity-provider-minecraft-client-secret=<value>
 *
 * URLs are fixed to Microsoft's consumer OAuth2 endpoints (consumer tenant required — using the
 * common or a corporate tenant will cause Xbox Live errors).
 */
class MinecraftIdentityProviderConfig(
    model: IdentityProviderModel,
    private val spiClientId: String? = null,
    private val spiClientSecret: String? = null,
) : OAuth2IdentityProviderConfig(model) {

    constructor() : this(IdentityProviderModel())

    var syncRealName: Boolean
        get() = config[SYNC_REAL_NAME]?.toBooleanStrictOrNull() ?: false
        set(value) {
            config[SYNC_REAL_NAME] = value.toString()
        }

    /** Admin UI value takes precedence; falls back to SPI/env-var value. */
    override fun getClientId(): String? =
        super.getClientId()?.takeIf { it.isNotBlank() } ?: spiClientId

    /** Admin UI value takes precedence; falls back to SPI/env-var value. */
    override fun getClientSecret(): String? =
        super.getClientSecret()?.takeIf { it.isNotBlank() } ?: spiClientSecret

    override fun getAuthorizationUrl(): String = "https://login.live.com/oauth20_authorize.srf"

    override fun getTokenUrl(): String = "https://login.live.com/oauth20_token.srf"

    override fun getDefaultScope(): String = "XboxLive.signin offline_access openid profile email"

    override fun validate(realm: RealmModel) {
        super.validate(realm)
        requireSupportedClientAuthMethod()
    }

    internal fun requireSupportedClientAuthMethod() {
        val configuredClientAuthMethod = clientAuthMethod

        require(configuredClientAuthMethod == CLIENT_SECRET_POST) {
            unsupportedClientAuthMethodMessage(configuredClientAuthMethod)
        }
    }

    internal fun requireSupportedClientAuthMethodForBroker() {
        val configuredClientAuthMethod = clientAuthMethod

        if (configuredClientAuthMethod != CLIENT_SECRET_POST) {
            throw IdentityBrokerException(
                unsupportedClientAuthMethodMessage(configuredClientAuthMethod)
            )
        }
    }

    private fun unsupportedClientAuthMethodMessage(configuredClientAuthMethod: String?): String =
        "Minecraft identity provider supports only `$CLIENT_SECRET_POST` " +
            "for the Microsoft token endpoint " +
            "(configuredClientAuthMethod=$configuredClientAuthMethod)."

    companion object {
        internal const val CLIENT_SECRET_POST = "client_secret_post"
        internal const val SYNC_REAL_NAME = "syncRealName"
    }
}
