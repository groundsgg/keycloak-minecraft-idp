package gg.grounds.keycloak.minecraft

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.keycloak.models.IdentityProviderModel
import org.keycloak.provider.ProviderConfigProperty

class MinecraftIdentityProviderConfigTest {

    @Test
    fun `default client auth method uses client secret post`() {
        val config = MinecraftIdentityProviderConfig()

        config.requireSupportedClientAuthMethod()

        assertEquals(MinecraftIdentityProviderConfig.CLIENT_SECRET_POST, config.clientAuthMethod)
    }

    @Test
    fun `sync real name defaults to disabled`() {
        val config = MinecraftIdentityProviderConfig()

        assertFalse(config.syncRealName)
    }

    @Test
    fun `sync real name can be enabled`() {
        val config = MinecraftIdentityProviderConfig().apply { syncRealName = true }

        assertTrue(config.syncRealName)
    }

    @Test
    fun `realm credentials override spi credentials`() {
        val config =
            MinecraftIdentityProviderConfig(
                    IdentityProviderModel(),
                    spiClientId = "spi-client-id",
                    spiClientSecret = "spi-client-secret",
                )
                .apply {
                    clientId = "realm-client-id"
                    clientSecret = "realm-client-secret"
                }

        assertEquals("realm-client-id", config.clientId)
        assertEquals("realm-client-secret", config.clientSecret)
    }

    @Test
    fun `blank realm credentials fall back to spi credentials`() {
        val providerConfig =
            MinecraftIdentityProviderConfig(
                    IdentityProviderModel(),
                    spiClientId = "spi-client-id",
                    spiClientSecret = "spi-client-secret",
                )
                .apply {
                    clientId = ""
                    clientSecret = ""
                }

        assertEquals("spi-client-id", providerConfig.clientId)
        assertEquals("spi-client-secret", providerConfig.clientSecret)
    }

    @Test
    fun `unsupported client auth methods are rejected`() {
        val unsupportedClientAuthMethods =
            listOf(
                "client_secret_basic",
                "client_secret_basic_unencoded",
                "client_secret_jwt",
                "private_key_jwt",
            )

        unsupportedClientAuthMethods.forEach { clientAuthMethod ->
            val config =
                MinecraftIdentityProviderConfig().apply { this.clientAuthMethod = clientAuthMethod }

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    config.requireSupportedClientAuthMethod()
                }

            assertContains(exception.message ?: "", clientAuthMethod)
            assertContains(
                exception.message ?: "",
                MinecraftIdentityProviderConfig.CLIENT_SECRET_POST,
            )
        }
    }

    @Test
    fun `factory exposes sync real name config property`() {
        val property =
            MinecraftIdentityProviderFactory().configProperties.single {
                it.name == MinecraftIdentityProviderConfig.SYNC_REAL_NAME
            }

        assertEquals("Sync Real Name", property.label)
        assertEquals(ProviderConfigProperty.BOOLEAN_TYPE, property.type)
        assertEquals(false, property.defaultValue)
    }
}
