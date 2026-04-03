package gg.grounds.keycloak.minecraft

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.MinecraftClient
import gg.grounds.keycloak.minecraft.api.XboxAuthApi
import gg.grounds.keycloak.minecraft.api.XboxAuthClient
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.keycloak.broker.provider.AbstractIdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.http.simple.SimpleHttpRequest
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.IdentityProviderSyncMode
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.sessions.AuthenticationSessionModel
import org.keycloak.vault.VaultCharSecret
import org.keycloak.vault.VaultRawSecret
import org.keycloak.vault.VaultStringSecret
import org.keycloak.vault.VaultTranscriber

class MinecraftIdentityProviderBehaviorTest {

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
        assertTrue(
            vault.secret.closed,
            "Vault secret should be closed after request authentication",
        )
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
        assertTrue(
            vault.secret.closed,
            "Vault secret should be closed after request authentication",
        )
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

        val context = extractIdentityFromProfile(provider, profile)

        assertEquals("minecraft-id", context.id)
        assertEquals("groundssteve", context.username)
    }

    @Test
    fun `extractIdentityFromProfile rejects missing id`() {
        val provider = createProvider()
        val profile = ObjectMapper().readTree("""{"name":"GroundsSteve"}""")

        val exception =
            assertFailsWith<IdentityBrokerException> {
                extractIdentityFromProfile(provider, profile)
            }

        assertEquals("Profile response missing 'id' field", exception.message)
    }

    @Test
    fun `extractIdentityFromProfile rejects missing name`() {
        val provider = createProvider()
        val profile = ObjectMapper().readTree("""{"id":"minecraft-id"}""")

        val exception =
            assertFailsWith<IdentityBrokerException> {
                extractIdentityFromProfile(provider, profile)
            }

        assertEquals("Profile response missing 'name' field", exception.message)
    }

    @Test
    fun `doGetFederatedIdentity wraps io failures as generic authentication failure`() {
        val provider =
            createProvider(
                xboxAuthClient =
                    object : XboxAuthClient {
                        override fun authenticateWithXbox(
                            microsoftAccessToken: String
                        ): XboxAuthApi.XboxAuthResponse {
                            throw IOException("upstream Xbox failure")
                        }

                        override fun obtainXstsToken(
                            xboxUserToken: String
                        ): XboxAuthApi.XboxAuthResponse = error("XSTS should not be called")
                    },
                minecraftClient = unusedMinecraftClient(),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolveFederatedIdentity(provider) }

        assertEquals("Minecraft authentication failed. Please try again.", exception.message)
        assertTrue(exception.cause is IOException)
        assertEquals("upstream Xbox failure", exception.cause?.message)
    }

    @Test
    fun `doGetFederatedIdentity restores interrupt flag when upstream call is interrupted`() {
        val provider =
            createProvider(
                xboxAuthClient =
                    object : XboxAuthClient {
                        override fun authenticateWithXbox(
                            microsoftAccessToken: String
                        ): XboxAuthApi.XboxAuthResponse {
                            throw InterruptedException("request interrupted")
                        }

                        override fun obtainXstsToken(
                            xboxUserToken: String
                        ): XboxAuthApi.XboxAuthResponse = error("XSTS should not be called")
                    },
                minecraftClient = unusedMinecraftClient(),
            )

        try {
            val exception =
                assertFailsWith<IdentityBrokerException> { resolveFederatedIdentity(provider) }

            assertEquals("Minecraft authentication was interrupted.", exception.message)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(exception.cause is InterruptedException)
        } finally {
            assertTrue(Thread.interrupted(), "Interrupted flag should be cleared after assertion")
            assertFalse(Thread.currentThread().isInterrupted)
        }
    }

    @Test
    fun `doGetFederatedIdentity rejects Bedrock users without gamertag`() {
        val provider =
            createProvider(
                xboxAuthClient =
                    object : XboxAuthClient {
                        override fun authenticateWithXbox(
                            microsoftAccessToken: String
                        ): XboxAuthApi.XboxAuthResponse =
                            xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")

                        override fun obtainXstsToken(
                            xboxUserToken: String
                        ): XboxAuthApi.XboxAuthResponse =
                            xboxResponse(
                                token = "xsts-token",
                                userHash = "xsts-uhs",
                                gamertag = null,
                            )
                    },
                minecraftClient =
                    object : MinecraftClient {
                        override fun authenticateWithMinecraft(
                            userHash: String,
                            xstsToken: String,
                        ): MinecraftApi.MinecraftAuthResponse =
                            MinecraftApi.MinecraftAuthResponse(
                                accessToken = "minecraft-token",
                                tokenType = "Bearer",
                                expiresIn = 3600,
                            )

                        override fun getOwnership(
                            minecraftAccessToken: String
                        ): MinecraftApi.Ownership =
                            MinecraftApi.Ownership(
                                entitlementNames = setOf("product_minecraft_bedrock"),
                                ownsJavaEdition = false,
                                ownsBedrockEdition = true,
                            )

                        override fun getProfile(
                            minecraftAccessToken: String
                        ): MinecraftApi.MinecraftProfile =
                            error("Profile should not be requested for Bedrock-only flow")
                    },
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolveFederatedIdentity(provider) }

        assertEquals("Could not retrieve Xbox Gamertag for Bedrock user", exception.message)
    }

    @Test
    fun `updateBrokeredUser syncs first and last name for new users`() {
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
            BrokeredIdentityContext("minecraft-id", provider.config).apply {
                authenticationSession = authenticationSession(isNewUser = true)
                firstName = "Lukas"
                lastName = "Jost"
            }

        provider.updateBrokeredUser(
            testKeycloakSession(RecordingVaultTranscriber(secretValue = null)),
            realmModel(),
            recordingUserModel(state),
            context,
        )

        assertEquals("Lukas", state.firstName)
        assertEquals("Jost", state.lastName)
    }

    @Test
    fun `updateBrokeredUser ignores names when real name sync is disabled`() {
        val provider = createProvider()
        val state = RecordingUserState(username = "minecraft-user")
        val context =
            BrokeredIdentityContext("minecraft-id", provider.config).apply {
                authenticationSession = authenticationSession(isNewUser = true)
                firstName = "Lukas"
                lastName = "Jost"
            }

        provider.updateBrokeredUser(
            testKeycloakSession(RecordingVaultTranscriber(secretValue = null)),
            realmModel(),
            recordingUserModel(state),
            context,
        )

        assertEquals(null, state.firstName)
        assertEquals(null, state.lastName)
    }

    @Test
    fun `updateBrokeredUser keeps existing names when sync mode is not force`() {
        val provider =
            createProvider(
                config =
                    MinecraftIdentityProviderConfig().apply {
                        isEnabled = true
                        clientId = "minecraft-client-id"
                        clientSecret = "minecraft-client-secret"
                        syncRealName = true
                        syncMode = IdentityProviderSyncMode.IMPORT
                    }
            )
        val state =
            RecordingUserState(
                username = "minecraft-user",
                firstName = "Existing",
                lastName = "Player",
            )
        val context =
            BrokeredIdentityContext("minecraft-id", provider.config).apply {
                authenticationSession = authenticationSession(isNewUser = false)
                firstName = "Lukas"
                lastName = "Jost"
            }

        provider.updateBrokeredUser(
            testKeycloakSession(RecordingVaultTranscriber(secretValue = null)),
            realmModel(),
            recordingUserModel(state),
            context,
        )

        assertEquals("Existing", state.firstName)
        assertEquals("Player", state.lastName)
    }

    private fun createProvider(
        config: MinecraftIdentityProviderConfig =
            MinecraftIdentityProviderConfig().apply {
                isEnabled = true
                clientId = "minecraft-client-id"
                clientSecret = "minecraft-client-secret"
            },
        session: KeycloakSession =
            testKeycloakSession(RecordingVaultTranscriber(secretValue = null)),
        xboxAuthClient: XboxAuthClient = unusedXboxAuthClient(),
        minecraftClient: MinecraftClient = unusedMinecraftClient(),
    ): MinecraftIdentityProvider =
        MinecraftIdentityProvider(session, config, xboxAuthClient, minecraftClient)

    private fun unusedXboxAuthClient(): XboxAuthClient =
        object : XboxAuthClient {
            override fun authenticateWithXbox(
                microsoftAccessToken: String
            ): XboxAuthApi.XboxAuthResponse = error("Xbox authentication should not be called")

            override fun obtainXstsToken(xboxUserToken: String): XboxAuthApi.XboxAuthResponse =
                error("XSTS should not be called")
        }

    private fun unusedMinecraftClient(): MinecraftClient =
        object : MinecraftClient {
            override fun authenticateWithMinecraft(
                userHash: String,
                xstsToken: String,
            ): MinecraftApi.MinecraftAuthResponse =
                error("Minecraft authentication should not be called")

            override fun getOwnership(minecraftAccessToken: String): MinecraftApi.Ownership =
                error("Ownership should not be called")

            override fun getProfile(minecraftAccessToken: String): MinecraftApi.MinecraftProfile =
                error("Profile should not be called")
        }

    private fun newSimpleHttpRequest(
        url: String = "https://login.live.com/oauth20_token.srf"
    ): SimpleHttpRequest {
        val simpleHttpMethodClass = Class.forName("org.keycloak.http.simple.SimpleHttpMethod")
        val postMethod =
            simpleHttpMethodClass.enumConstants.first { enumConstant ->
                (enumConstant as Enum<*>).name == "POST"
            }
        val constructor =
            SimpleHttpRequest::class.java.declaredConstructors.single { candidate ->
                candidate.parameterTypes.size == 6
            }
        constructor.isAccessible = true
        return constructor.newInstance(url, postMethod, null, null, 0L, ObjectMapper())
            as SimpleHttpRequest
    }

    private fun testKeycloakSession(vaultTranscriber: VaultTranscriber): KeycloakSession =
        Proxy.newProxyInstance(
            KeycloakSession::class.java.classLoader,
            arrayOf(KeycloakSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "vault" -> vaultTranscriber
                "toString" -> "TestKeycloakSession"
                "hashCode" -> 0
                "equals" -> false
                else ->
                    throw UnsupportedOperationException(
                        "Unexpected KeycloakSession method invoked during test (method=${method.name})"
                    )
            }
        } as KeycloakSession

    private fun authenticationSession(isNewUser: Boolean): AuthenticationSessionModel =
        Proxy.newProxyInstance(
            AuthenticationSessionModel::class.java.classLoader,
            arrayOf(AuthenticationSessionModel::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAuthNote" ->
                    if (args?.get(0) == AbstractIdentityProvider.BROKER_REGISTERED_NEW_USER) {
                        isNewUser.toString()
                    } else {
                        null
                    }
                "toString" -> "TestAuthenticationSession"
                "hashCode" -> 0
                "equals" -> false
                else ->
                    throw UnsupportedOperationException(
                        "Unexpected AuthenticationSessionModel method invoked during test (method=${method.name})"
                    )
            }
        } as AuthenticationSessionModel

    private fun realmModel(): RealmModel =
        Proxy.newProxyInstance(
            RealmModel::class.java.classLoader,
            arrayOf(RealmModel::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "TestRealmModel"
                "hashCode" -> 0
                "equals" -> false
                else ->
                    throw UnsupportedOperationException(
                        "Unexpected RealmModel method invoked during test (method=${method.name})"
                    )
            }
        } as RealmModel

    private fun recordingUserModel(state: RecordingUserState): UserModel =
        Proxy.newProxyInstance(UserModel::class.java.classLoader, arrayOf(UserModel::class.java)) {
            _,
            method,
            args ->
            when (method.name) {
                "getUsername" -> state.username
                "getFirstName" -> state.firstName
                "setFirstName" -> {
                    state.firstName = args?.get(0) as String?
                    null
                }
                "getLastName" -> state.lastName
                "setLastName" -> {
                    state.lastName = args?.get(0) as String?
                    null
                }
                "getEmail" -> state.email
                "setEmail" -> {
                    state.email = args?.get(0) as String?
                    null
                }
                "setEmailVerified" -> {
                    state.emailVerified = args?.get(0) as Boolean
                    null
                }
                "toString" -> "RecordingUserModel"
                "hashCode" -> 0
                "equals" -> false
                else ->
                    throw UnsupportedOperationException(
                        "Unexpected UserModel method invoked during test (method=${method.name})"
                    )
            }
        } as UserModel

    private fun resolveFederatedIdentity(
        provider: MinecraftIdentityProvider,
        accessToken: String = "ms-access-token",
    ): BrokeredIdentityContext =
        try {
            MinecraftIdentityProvider::class
                .java
                .getDeclaredMethod("doGetFederatedIdentity", String::class.java)
                .apply { isAccessible = true }
                .invoke(provider, accessToken) as BrokeredIdentityContext
        } catch (exception: InvocationTargetException) {
            throw (exception.cause ?: exception)
        }

    private fun extractIdentityFromProfile(
        provider: MinecraftIdentityProvider,
        profile: com.fasterxml.jackson.databind.JsonNode,
    ): BrokeredIdentityContext =
        try {
            MinecraftIdentityProvider::class
                .java
                .getDeclaredMethod(
                    "extractIdentityFromProfile",
                    org.keycloak.events.EventBuilder::class.java,
                    com.fasterxml.jackson.databind.JsonNode::class.java,
                )
                .apply { isAccessible = true }
                .invoke(provider, null, profile) as BrokeredIdentityContext
        } catch (exception: InvocationTargetException) {
            throw (exception.cause ?: exception)
        }

    private fun xboxResponse(
        token: String,
        userHash: String?,
        gamertag: String? = null,
        xboxUserId: String? = null,
    ): XboxAuthApi.XboxAuthResponse =
        XboxAuthApi.XboxAuthResponse(
            token = token,
            displayClaims =
                XboxAuthApi.DisplayClaims(
                    xui =
                        listOf(
                            XboxAuthApi.XuiClaim(uhs = userHash, gtg = gamertag, xid = xboxUserId)
                        )
                ),
        )

    private class RecordingVaultTranscriber(secretValue: String?) : VaultTranscriber {
        val requestedSecretKeys = mutableListOf<String>()
        val secret = RecordingVaultStringSecret(secretValue)

        override fun getRawSecret(key: String): VaultRawSecret =
            throw UnsupportedOperationException("Raw secret access is not expected in these tests")

        override fun getCharSecret(key: String): VaultCharSecret =
            throw UnsupportedOperationException("Char secret access is not expected in these tests")

        override fun getStringSecret(key: String): VaultStringSecret {
            requestedSecretKeys += key
            return secret
        }
    }

    private class RecordingVaultStringSecret(secretValue: String?) : VaultStringSecret {
        private val optionalSecret = Optional.ofNullable(secretValue)
        var closed = false

        override fun get(): Optional<String> = optionalSecret

        override fun close() {
            closed = true
        }
    }

    private data class RecordingUserState(
        val username: String,
        var firstName: String? = null,
        var lastName: String? = null,
        var email: String? = null,
        var emailVerified: Boolean = false,
    )
}
