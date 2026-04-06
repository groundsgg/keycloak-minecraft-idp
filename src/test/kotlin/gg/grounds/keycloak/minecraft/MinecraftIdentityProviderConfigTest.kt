package gg.grounds.keycloak.minecraft

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `partner relying party defaults to unset`() {
        val config = MinecraftIdentityProviderConfig()

        assertNull(config.partnerRelyingParty)
    }

    @Test
    fun `partner relying party can be enabled`() {
        val config =
            MinecraftIdentityProviderConfig().apply {
                partnerRelyingParty = "https://grounds.example.com"
            }

        assertEquals("https://grounds.example.com", config.partnerRelyingParty)
    }

    @Test
    fun `partner xsts private key falls back to spi value`() {
        val config =
            MinecraftIdentityProviderConfig(
                IdentityProviderModel(),
                spiPartnerXstsPrivateKey = "file:/opt/keycloak/conf/xsts-private.pem",
            )

        assertEquals("file:/opt/keycloak/conf/xsts-private.pem", config.partnerXstsPrivateKey)
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
    fun `missing partner relying party is rejected`() {
        val config = MinecraftIdentityProviderConfig()

        val exception =
            assertFailsWith<IllegalArgumentException> { config.requirePartnerRelyingParty() }

        assertContains(exception.message ?: "", "partnerRelyingParty")
        assertContains(exception.message ?: "", "ptx")
    }

    @Test
    fun `factory exposes partner relying party config property`() {
        val property =
            MinecraftIdentityProviderFactory().configProperties.single {
                it.name == MinecraftIdentityProviderConfig.PARTNER_RELYING_PARTY
            }

        assertEquals("Partner Relying Party", property.label)
        assertEquals(ProviderConfigProperty.STRING_TYPE, property.type)
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

    @Test
    fun `factory exposes partner xsts private key config property`() {
        val property =
            MinecraftIdentityProviderFactory().configProperties.single {
                it.name == MinecraftIdentityProviderConfig.PARTNER_XSTS_PRIVATE_KEY
            }

        assertEquals("Partner XSTS Private Key", property.label)
        assertEquals(ProviderConfigProperty.STRING_TYPE, property.type)
    }
}
