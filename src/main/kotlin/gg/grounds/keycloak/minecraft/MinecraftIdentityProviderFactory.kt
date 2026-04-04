package gg.grounds.keycloak.minecraft

import gg.grounds.keycloak.minecraft.api.SharedApiClient
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
 * Reads optional server-level defaults for client credentials from SPI configuration, allowing
 * credentials to be injected via Kubernetes Secrets instead of storing them in the realm database.
 *
 * Environment variables (Kubernetes Secret usage): KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_ID
 * KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_SECRET
 *
 * Or as kc.sh flags: --spi-identity-provider-minecraft-client-id=<value>
 * --spi-identity-provider-minecraft-client-secret=<value>
 *
 * Admin UI values always take precedence over these server-level defaults.
 */
class MinecraftIdentityProviderFactory :
    AbstractIdentityProviderFactory<MinecraftIdentityProvider>() {

    private var spiClientId: String? = null
    private var spiClientSecret: String? = null

    /** Called once at server startup. Reads SPI-level config (env vars / kc.sh flags). */
    override fun init(config: Config.Scope) {
        // Keycloak maps KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_ID → "client-id" (kebab-case)
        spiClientId = config.get("client-id")
        spiClientSecret = config.get("client-secret")

        when {
            spiClientId != null && spiClientSecret != null ->
                logger.info(
                    "Loaded SPI credentials successfully (provider=$PROVIDER_ID, clientIdConfigured=true, clientSecretConfigured=true)"
                )
            spiClientId != null ->
                logger.warn(
                    "Loaded SPI credentials partially (provider=$PROVIDER_ID, clientIdConfigured=true, clientSecretConfigured=false)"
                )
            spiClientSecret != null ->
                logger.warn(
                    "Loaded SPI credentials partially (provider=$PROVIDER_ID, clientIdConfigured=false, clientSecretConfigured=true)"
                )
            else ->
                logger.debug(
                    "Loaded SPI credentials skipped (provider=$PROVIDER_ID, clientIdConfigured=false, clientSecretConfigured=false)"
                )
        }
    }

    override fun getName(): String = PROVIDER_NAME

    override fun getId(): String = PROVIDER_ID

    override fun getConfigProperties(): List<ProviderConfigProperty> =
        ProviderConfigurationBuilder.create()
            .property()
            .name(MinecraftIdentityProviderConfig.SYNC_REAL_NAME)
            .label("Sync Real Name")
            .helpText(
                "Imports and stores Microsoft given and family name claims when available. " +
                    "When disabled, first and last name are ignored."
            )
            .type(ProviderConfigProperty.BOOLEAN_TYPE)
            .defaultValue(false)
            .add()
            .build()

    override fun create(
        session: KeycloakSession,
        model: IdentityProviderModel,
    ): MinecraftIdentityProvider =
        MinecraftIdentityProvider(
            session,
            MinecraftIdentityProviderConfig(model, spiClientId, spiClientSecret),
        )

    override fun createConfig(): MinecraftIdentityProviderConfig = MinecraftIdentityProviderConfig()

    override fun close() {
        SharedApiClient.close()
        super.close()
    }

    companion object {
        const val PROVIDER_ID = "minecraft"
        const val PROVIDER_NAME = "Minecraft"
        private val logger = Logger.getLogger(MinecraftIdentityProviderFactory::class.java)
    }
}
