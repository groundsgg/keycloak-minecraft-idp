package gg.grounds.keycloak.minecraft

import org.jboss.logging.Logger
import org.keycloak.Config
import org.keycloak.broker.provider.AbstractIdentityProviderFactory
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.KeycloakSession
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigurationBuilder

/**
 * Factory for creating Minecraft Identity Provider instances.
 *
 * Reads optional server-level defaults for client credentials from SPI configuration,
 * allowing credentials to be injected via Kubernetes Secrets instead of storing them
 * in the realm database.
 *
 * Environment variables (Kubernetes Secret usage):
 *   KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_ID
 *   KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_SECRET
 *
 * Or as kc.sh flags:
 *   --spi-identity-provider-minecraft-client-id=<value>
 *   --spi-identity-provider-minecraft-client-secret=<value>
 *
 * Admin UI values always take precedence over these server-level defaults.
 */
class MinecraftIdentityProviderFactory :
    AbstractIdentityProviderFactory<MinecraftIdentityProvider>() {

    private var spiClientId: String? = null
    private var spiClientSecret: String? = null

    /**
     * Called once at server startup. Reads SPI-level config (env vars / kc.sh flags).
     */
    override fun init(config: Config.Scope) {
        spiClientId = config.get("clientId")
        spiClientSecret = config.get("clientSecret")

        when {
            spiClientId != null && spiClientSecret != null ->
                logger.info("Minecraft IdP: client credentials loaded from SPI configuration")
            spiClientId != null ->
                logger.warn("Minecraft IdP: client-id set via SPI config but client-secret is missing")
            spiClientSecret != null ->
                logger.warn("Minecraft IdP: client-secret set via SPI config but client-id is missing")
            else ->
                logger.debug("Minecraft IdP: no SPI-level credentials configured, using Admin UI values")
        }
    }

    override fun getName(): String = PROVIDER_NAME

    override fun getId(): String = PROVIDER_ID

    override fun create(
        session: KeycloakSession,
        model: IdentityProviderModel,
    ): MinecraftIdentityProvider =
        MinecraftIdentityProvider(session, MinecraftIdentityProviderConfig(model, spiClientId, spiClientSecret))

    override fun createConfig(): MinecraftIdentityProviderConfig = MinecraftIdentityProviderConfig()

    override fun getConfigProperties(): List<ProviderConfigProperty> =
        ProviderConfigurationBuilder.create()
            .property()
            .name("clientId")
            .label("Client ID")
            .helpText(
                "The Client ID from your Microsoft Azure App Registration. " +
                    "Leave blank to use the server-level value from " +
                    "KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_ID."
            )
            .type(ProviderConfigProperty.STRING_TYPE)
            .add()
            .property()
            .name("clientSecret")
            .label("Client Secret")
            .helpText(
                "The Client Secret from your Microsoft Azure App Registration. " +
                    "Leave blank to use the server-level value from " +
                    "KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_SECRET."
            )
            .type(ProviderConfigProperty.PASSWORD)
            .secret(true)
            .add()
            .build()

    companion object {
        const val PROVIDER_ID = "minecraft"
        const val PROVIDER_NAME = "Minecraft"
        private val logger = Logger.getLogger(MinecraftIdentityProviderFactory::class.java)
    }
}
