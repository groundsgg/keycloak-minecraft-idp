package gg.grounds.keycloak.minecraft

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.testsupport.FakeMinecraftClient
import gg.grounds.keycloak.minecraft.testsupport.FakeXboxAuthClient
import gg.grounds.keycloak.minecraft.testsupport.RecordingUserState
import gg.grounds.keycloak.minecraft.testsupport.RecordingVaultTranscriber
import gg.grounds.keycloak.minecraft.testsupport.authenticationSession
import gg.grounds.keycloak.minecraft.testsupport.invokeExtractIdentityFromProfile
import gg.grounds.keycloak.minecraft.testsupport.newSimpleHttpRequest
import gg.grounds.keycloak.minecraft.testsupport.realmModel
import gg.grounds.keycloak.minecraft.testsupport.recordingUserModel
import gg.grounds.keycloak.minecraft.testsupport.testKeycloakSession
import gg.grounds.keycloak.minecraft.testsupport.xboxResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.models.IdentityProviderModel

class MinecraftIdentityProviderTest {

    @Test
    fun `authenticateTokenRequest adds client secret post parameters`() {
        val vault = RecordingVaultTranscriber(secretValue = null)
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        clientId = "realm-client-id"
                        clientSecret = "realm-client-secret"
                    },
                session = testKeycloakSession(vault),
            )

        val request = provider.authenticateTokenRequest(newSimpleHttpRequest())

        assertEquals("realm-client-id", request.getParam("client_id"))
        assertEquals("realm-client-secret", request.getParam("client_secret"))
        assertEquals(listOf("realm-client-secret"), vault.requestedSecretKeys)
        assertTrue(vault.secret.closed)
    }

    @Test
    fun `authenticateTokenRequest uses vault resolved secret`() {
        val vault = RecordingVaultTranscriber(secretValue = "vault-secret")
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        clientId = "realm-client-id"
                        clientSecret = "vault:keycloak/minecraft"
                    },
                session = testKeycloakSession(vault),
            )

        val request = provider.authenticateTokenRequest(newSimpleHttpRequest())

        assertEquals("realm-client-id", request.getParam("client_id"))
        assertEquals("vault-secret", request.getParam("client_secret"))
    }

    @Test
    fun `authenticateTokenRequest rejects unresolved vault secret`() {
        val vault = RecordingVaultTranscriber(secretValue = null)
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        clientId = "realm-client-id"
                        clientSecret = "vault:keycloak/minecraft"
                    },
                session = testKeycloakSession(vault),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                provider.authenticateTokenRequest(newSimpleHttpRequest())
            }

        assertEquals(
            "Minecraft identity provider could not resolve `clientSecret` from Keycloak vault.",
            exception.message,
        )
        assertEquals(listOf("vault:keycloak/minecraft"), vault.requestedSecretKeys)
        assertTrue(vault.secret.closed)
    }

    @Test
    fun `authenticateTokenRequest rejects blank vault secret`() {
        val vault = RecordingVaultTranscriber(secretValue = "")
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        clientId = "realm-client-id"
                        clientSecret = "vault:keycloak/minecraft"
                    },
                session = testKeycloakSession(vault),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                provider.authenticateTokenRequest(newSimpleHttpRequest())
            }

        assertEquals(
            "Minecraft identity provider could not resolve `clientSecret` from Keycloak vault.",
            exception.message,
        )
        assertEquals(listOf("vault:keycloak/minecraft"), vault.requestedSecretKeys)
        assertTrue(vault.secret.closed)
    }

    @Test
    fun `authenticateTokenRequest uses spi fallback credentials`() {
        val vault = RecordingVaultTranscriber(secretValue = null)
        val config =
            MinecraftIdentityProviderConfig(
                IdentityProviderModel(),
                spiClientId = "spi-client-id",
                spiClientSecret = "spi-client-secret",
            )
        val provider = createProvider(config = config, session = testKeycloakSession(vault))

        val request = provider.authenticateTokenRequest(newSimpleHttpRequest())

        assertEquals("spi-client-id", request.getParam("client_id"))
        assertEquals("spi-client-secret", request.getParam("client_secret"))
    }

    @Test
    fun `authenticateTokenRequest rejects unsupported client auth method as broker error`() {
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        clientId = "realm-client-id"
                        clientSecret = "realm-client-secret"
                        clientAuthMethod = "client_secret_basic"
                    }
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                provider.authenticateTokenRequest(newSimpleHttpRequest())
            }

        assertEquals(
            "Minecraft identity provider supports only `client_secret_post` " +
                "for the Microsoft token endpoint " +
                "(configuredClientAuthMethod=client_secret_basic).",
            exception.message,
        )
    }

    @Test
    fun `authenticateTokenRequest rejects missing client id`() {
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        clientId = ""
                        clientSecret = "realm-client-secret"
                    }
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                provider.authenticateTokenRequest(newSimpleHttpRequest())
            }

        assertEquals(
            "Minecraft identity provider is missing `clientId`. Configure it in the Admin UI or via SPI (KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_ID).",
            exception.message,
        )
    }

    @Test
    fun `authenticateTokenRequest rejects missing client secret`() {
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        clientId = "realm-client-id"
                        clientSecret = ""
                    }
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                provider.authenticateTokenRequest(newSimpleHttpRequest())
            }

        assertEquals(
            "Minecraft identity provider is missing `clientSecret`. Configure it in the Admin UI or via SPI (KC_SPI_IDENTITY_PROVIDER_MINECRAFT_CLIENT_SECRET).",
            exception.message,
        )
    }

    @Test
    fun `extractIdentityFromProfile maps id and username`() {
        val provider = createProvider()
        val profile = ObjectMapper().readTree("""{"id":"minecraft-id","name":"GroundsSteve"}""")

        val context = invokeExtractIdentityFromProfile(provider, profile)

        assertEquals("minecraft-id", context.id)
        assertEquals("groundssteve", context.username)
    }

    @Test
    fun `extractIdentityFromProfile rejects missing id`() {
        val provider = createProvider()
        val profile = ObjectMapper().readTree("""{"name":"GroundsSteve"}""")

        val exception =
            assertFailsWith<IdentityBrokerException> {
                invokeExtractIdentityFromProfile(provider, profile)
            }

        assertEquals("Profile response missing 'id' field", exception.message)
    }

    @Test
    fun `extractIdentityFromProfile rejects missing name`() {
        val provider = createProvider()
        val profile = ObjectMapper().readTree("""{"id":"minecraft-id"}""")

        val exception =
            assertFailsWith<IdentityBrokerException> {
                invokeExtractIdentityFromProfile(provider, profile)
            }

        assertEquals("Profile response missing 'name' field", exception.message)
    }

    @Test
    fun `enriches broker profile from microsoft id token`() {
        val provider = createProvider(syncRealName = true)
        val response =
            """
            {
              "access_token":"ms-access-token",
              "id_token":"${idToken(givenName = "Alex", familyName = "Player", email = "alex@example.com")}"
            }
            """
                .trimIndent()

        val context = provider.getFederatedIdentity(response)

        assertEquals("groundssteve", context.username)
        assertEquals("alex@example.com", context.email)
        assertEquals("Alex", context.firstName)
        assertEquals("Player", context.lastName)
    }

    @Test
    fun `ignores microsoft real name claims when sync is disabled`() {
        val provider = createProvider()
        val response =
            """
            {
              "access_token":"ms-access-token",
              "id_token":"${idToken(givenName = "Alex", familyName = "Player", email = "alex@example.com")}"
            }
            """
                .trimIndent()

        val context = provider.getFederatedIdentity(response)

        assertEquals("alex@example.com", context.email)
        assertNull(context.firstName)
        assertNull(context.lastName)
    }

    @Test
    fun `does not infer split names from microsoft display name`() {
        val provider = createProvider(syncRealName = true)
        val response =
            """
            {
              "access_token":"ms-access-token",
              "id_token":"${idToken(name = "Lukas Jost", email = "lukas@example.com")}"
            }
            """
                .trimIndent()

        val context = provider.getFederatedIdentity(response)

        assertEquals("lukas@example.com", context.email)
        assertNull(context.firstName)
        assertNull(context.lastName)
    }

    @Test
    fun `updateBrokeredUser syncs names through provider hook`() {
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        isEnabled = true
                        clientId = "minecraft-client-id"
                        clientSecret = "minecraft-client-secret"
                        syncRealName = true
                    }
            )
        val state = RecordingUserState(username = "minecraft-user")
        val context =
            org.keycloak.broker.provider
                .BrokeredIdentityContext("minecraft-id", provider.config)
                .apply {
                    authenticationSession = authenticationSession(isNewUser = true)
                    firstName = "Lukas"
                    lastName = "Jost"
                }

        provider.updateBrokeredUser(
            testKeycloakSession(),
            realmModel(),
            recordingUserModel(state),
            context,
        )

        assertEquals("Lukas", state.firstName)
        assertEquals("Jost", state.lastName)
    }

    @Test
    fun `updateBrokeredUser syncs managed attributes through provider hook`() {
        val provider = createProvider()
        val state =
            RecordingUserState(
                username = "minecraft-user",
                attributes =
                    mutableMapOf(
                        "minecraft_login_identity" to mutableListOf("java"),
                        "minecraft_java_uuid" to
                            mutableListOf("12345678-9012-3456-7890-123456789012"),
                        "custom_attribute" to mutableListOf("preserved"),
                    ),
            )
        val context =
            org.keycloak.broker.provider
                .BrokeredIdentityContext("minecraft-id", provider.config)
                .apply {
                    authenticationSession = authenticationSession(isNewUser = false)
                    setUserAttribute("minecraft_login_identity", "bedrock")
                    setUserAttribute("minecraft_java_owned", "false")
                    setUserAttribute("minecraft_bedrock_owned", "true")
                    setUserAttribute("xbox_gamertag", "BedrockTag")
                }

        provider.updateBrokeredUser(
            testKeycloakSession(),
            realmModel(),
            recordingUserModel(state),
            context,
        )

        assertEquals(listOf("bedrock"), state.attributes["minecraft_login_identity"]?.toList())
        assertEquals(listOf("false"), state.attributes["minecraft_java_owned"]?.toList())
        assertEquals(listOf("true"), state.attributes["minecraft_bedrock_owned"]?.toList())
        assertEquals(listOf("BedrockTag"), state.attributes["xbox_gamertag"]?.toList())
        assertEquals(null, state.attributes["minecraft_java_uuid"])
        assertEquals(listOf("preserved"), state.attributes["custom_attribute"]?.toList())
    }

    private fun createProvider(
        config: MinecraftIdentityProviderConfig =
            MinecraftIdentityProviderConfig().apply {
                isEnabled = true
                clientId = "minecraft-client-id"
                clientSecret = "minecraft-client-secret"
            },
        session: org.keycloak.models.KeycloakSession = testKeycloakSession(),
        syncRealName: Boolean = config.syncRealName,
    ): MinecraftIdentityProvider {
        config.syncRealName = syncRealName
        val xboxAuthClient =
            FakeXboxAuthClient(
                authenticateHandler = {
                    xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                },
                xstsHandler = {
                    xboxResponse(
                        token = "xsts-token",
                        userHash = "xsts-uhs",
                        gamertag = "GroundsTag",
                    )
                },
            )
        val minecraftClient =
            FakeMinecraftClient(
                authenticateHandler = { _, _ ->
                    MinecraftApi.MinecraftAuthResponse(
                        accessToken = "minecraft-token",
                        tokenType = "Bearer",
                        expiresIn = 3600,
                    )
                },
                ownershipHandler = {
                    MinecraftApi.Ownership(
                        entitlementNames = setOf("product_minecraft"),
                        ownsJavaEdition = true,
                        ownsBedrockEdition = false,
                    )
                },
                profileHandler = {
                    MinecraftApi.MinecraftProfile(
                        id = "12345678901234567890123456789012",
                        name = "GroundsSteve",
                        skins = null,
                        capes = null,
                    )
                },
            )

        return MinecraftIdentityProvider(session, config, xboxAuthClient, minecraftClient)
    }

    private fun idToken(
        email: String,
        givenName: String? = null,
        familyName: String? = null,
        name: String? = null,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = buildList {
            add("\"sub\":\"microsoft-user\"")
            name?.let { add("\"name\":\"$it\"") }
            givenName?.let { add("\"given_name\":\"$it\"") }
            familyName?.let { add("\"family_name\":\"$it\"") }
            add("\"email\":\"$email\"")
        }
        val payload =
            encoder.encodeToString(
                """
                {
                  ${claims.joinToString(",\n                  ")}
                }
                """
                    .trimIndent()
                    .toByteArray()
            )
        val signature = encoder.encodeToString("signature".toByteArray())
        return "$header.$payload.$signature"
    }
}
