package gg.grounds.keycloak.minecraft

import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.MinecraftClient
import gg.grounds.keycloak.minecraft.api.XboxAuthApi
import gg.grounds.keycloak.minecraft.api.XboxAuthClient
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.broker.provider.IdentityBrokerException
import org.keycloak.models.KeycloakSession

class MinecraftIdentityProviderFlowTest {

    @Test
    fun `resolves Java identity successfully`() {
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
                        xboxUserId = "281467",
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

        val context = resolveFederatedIdentity(createProvider(xboxAuthClient, minecraftClient))

        assertEquals(listOf("ms-access-token"), xboxAuthClient.microsoftAccessTokens)
        assertEquals(listOf("xbox-user-token"), xboxAuthClient.xboxUserTokens)
        assertEquals(listOf("xsts-uhs" to "xsts-token"), minecraftClient.minecraftAuthRequests)
        assertEquals(listOf("minecraft-token"), minecraftClient.ownershipRequests)
        assertEquals(listOf("minecraft-token"), minecraftClient.profileRequests)
        assertEquals("xbox-281467", context.id)
        assertEquals("xbox-281467", context.brokerUserId)
        assertEquals("groundssteve", context.username)
        assertEquals("java", context.getUserAttribute("minecraft_login_identity"))
        assertEquals("true", context.getUserAttribute("minecraft_java_owned"))
        assertEquals("false", context.getUserAttribute("minecraft_bedrock_owned"))
        assertEquals(
            "12345678-9012-3456-7890-123456789012",
            context.getUserAttribute("minecraft_java_uuid"),
        )
        assertEquals("GroundsSteve", context.getUserAttribute("minecraft_java_username"))
        assertEquals("GroundsTag", context.getUserAttribute("xbox_gamertag"))
        assertEquals("281467", context.getUserAttribute("xbox_user_id"))
    }

    @Test
    fun `enriches broker profile from microsoft id token`() {
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
                        xboxUserId = "281467",
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

        val provider = createProvider(xboxAuthClient, minecraftClient, syncRealName = true)
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
                        xboxUserId = "281467",
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

        val provider = createProvider(xboxAuthClient, minecraftClient)
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
                        xboxUserId = "281467",
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

        val provider = createProvider(xboxAuthClient, minecraftClient, syncRealName = true)
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
    fun `falls back to Bedrock identity when Java profile is missing`() {
        val xboxAuthClient =
            FakeXboxAuthClient(
                authenticateHandler = {
                    xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                },
                xstsHandler = {
                    xboxResponse(
                        token = "xsts-token",
                        userHash = "xsts-uhs",
                        gamertag = "BedrockTag",
                        xboxUserId = null,
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
                        entitlementNames = setOf("product_minecraft", "product_minecraft_bedrock"),
                        ownsJavaEdition = true,
                        ownsBedrockEdition = true,
                    )
                },
                profileHandler = {
                    throw MinecraftApi.MinecraftProfileNotFoundException(
                        "The user has no Java profile"
                    )
                },
            )

        val context = resolveFederatedIdentity(createProvider(xboxAuthClient, minecraftClient))

        assertEquals("xboxuhs-xsts-uhs", context.id)
        assertEquals("xboxuhs-xsts-uhs", context.brokerUserId)
        assertEquals("bedrocktag", context.username)
        assertEquals("bedrock", context.getUserAttribute("minecraft_login_identity"))
        assertEquals("true", context.getUserAttribute("minecraft_java_owned"))
        assertEquals("true", context.getUserAttribute("minecraft_bedrock_owned"))
        assertEquals("BedrockTag", context.getUserAttribute("xbox_gamertag"))
        assertNull(context.getUserAttribute("xbox_user_id"))
        assertNull(context.getUserAttribute("minecraft_java_username"))
    }

    @Test
    fun `rejects accounts without Minecraft entitlement`() {
        val xboxAuthClient =
            FakeXboxAuthClient(
                authenticateHandler = {
                    xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                },
                xstsHandler = {
                    xboxResponse(token = "xsts-token", userHash = "xsts-uhs", gamertag = "NoGame")
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
                        entitlementNames = emptySet(),
                        ownsJavaEdition = false,
                        ownsBedrockEdition = false,
                    )
                },
                profileHandler = {
                    error("Profile should not be requested when no entitlements exist")
                },
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                resolveFederatedIdentity(createProvider(xboxAuthClient, minecraftClient))
            }

        assertEquals(
            "This Microsoft account does not have a Minecraft Java or Bedrock entitlement.",
            exception.message,
        )
        assertEquals(emptyList(), minecraftClient.profileRequests)
    }

    @Test
    fun `reports Java profile setup requirement when Bedrock fallback is unavailable`() {
        val xboxAuthClient =
            FakeXboxAuthClient(
                authenticateHandler = {
                    xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                },
                xstsHandler = {
                    xboxResponse(token = "xsts-token", userHash = "xsts-uhs", gamertag = "JavaOnly")
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
                    throw MinecraftApi.MinecraftProfileNotFoundException(
                        "The user has no Java profile"
                    )
                },
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                resolveFederatedIdentity(createProvider(xboxAuthClient, minecraftClient))
            }

        assertEquals(
            "Your account has Minecraft Java Edition but no Java profile exists yet. " +
                "Please log into the Minecraft Launcher once to set up your profile.",
            exception.message,
        )
    }

    @Test
    fun `surfaces Xbox auth errors with provider message`() {
        val xboxAuthClient =
            FakeXboxAuthClient(
                authenticateHandler = {
                    throw XboxAuthApi.XboxAuthException(
                        errorCode = 2148916233L,
                        rawMessage = "no-xbox-account",
                        redirectUrl = null,
                    )
                },
                xstsHandler = {
                    error("XSTS should not be requested when Xbox authentication fails")
                },
            )
        val minecraftClient =
            FakeMinecraftClient(
                authenticateHandler = { _, _ ->
                    error("Minecraft authentication should not be reached")
                },
                ownershipHandler = { error("Ownership should not be reached") },
                profileHandler = { error("Profile should not be reached") },
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                resolveFederatedIdentity(createProvider(xboxAuthClient, minecraftClient))
            }

        assertEquals(
            "This Microsoft account has no Xbox account. Please create one at xbox.com/live",
            exception.message,
        )
    }

    @Test
    fun `rejects XSTS responses without a user hash`() {
        val xboxAuthClient =
            FakeXboxAuthClient(
                authenticateHandler = {
                    xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                },
                xstsHandler = {
                    xboxResponse(token = "xsts-token", userHash = null, gamertag = "MissingHash")
                },
            )
        val minecraftClient =
            FakeMinecraftClient(
                authenticateHandler = { _, _ ->
                    error("Minecraft authentication should not be reached")
                },
                ownershipHandler = { error("Ownership should not be reached") },
                profileHandler = { error("Profile should not be reached") },
            )

        val exception =
            assertFailsWith<IdentityBrokerException> {
                resolveFederatedIdentity(createProvider(xboxAuthClient, minecraftClient))
            }

        assertEquals("XSTS response did not return a user hash", exception.message)
    }

    private fun createProvider(
        xboxAuthClient: XboxAuthClient,
        minecraftClient: MinecraftClient,
        syncRealName: Boolean = false,
    ): MinecraftIdentityProvider =
        MinecraftIdentityProvider(
            testKeycloakSession(),
            MinecraftIdentityProviderConfig().apply {
                isEnabled = true
                clientId = "minecraft-client-id"
                clientSecret = "minecraft-client-secret"
                this.syncRealName = syncRealName
            },
            xboxAuthClient,
            minecraftClient,
        )

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

    private fun testKeycloakSession(): KeycloakSession =
        Proxy.newProxyInstance(
            KeycloakSession::class.java.classLoader,
            arrayOf(KeycloakSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "TestKeycloakSession"
                "hashCode" -> 0
                "equals" -> false
                else ->
                    throw UnsupportedOperationException(
                        "Unexpected KeycloakSession method invoked during test (method=${method.name})"
                    )
            }
        } as KeycloakSession

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

    private class FakeXboxAuthClient(
        private val authenticateHandler: (String) -> XboxAuthApi.XboxAuthResponse,
        private val xstsHandler: (String) -> XboxAuthApi.XboxAuthResponse,
    ) : XboxAuthClient {
        val microsoftAccessTokens = mutableListOf<String>()
        val xboxUserTokens = mutableListOf<String>()

        override fun authenticateWithXbox(
            microsoftAccessToken: String
        ): XboxAuthApi.XboxAuthResponse {
            microsoftAccessTokens += microsoftAccessToken
            return authenticateHandler(microsoftAccessToken)
        }

        override fun obtainXstsToken(xboxUserToken: String): XboxAuthApi.XboxAuthResponse {
            xboxUserTokens += xboxUserToken
            return xstsHandler(xboxUserToken)
        }
    }

    private class FakeMinecraftClient(
        private val authenticateHandler: (String, String) -> MinecraftApi.MinecraftAuthResponse,
        private val ownershipHandler: (String) -> MinecraftApi.Ownership,
        private val profileHandler: (String) -> MinecraftApi.MinecraftProfile,
    ) : MinecraftClient {
        val minecraftAuthRequests = mutableListOf<Pair<String, String>>()
        val ownershipRequests = mutableListOf<String>()
        val profileRequests = mutableListOf<String>()

        override fun authenticateWithMinecraft(
            userHash: String,
            xstsToken: String,
        ): MinecraftApi.MinecraftAuthResponse {
            minecraftAuthRequests += userHash to xstsToken
            return authenticateHandler(userHash, xstsToken)
        }

        override fun getOwnership(minecraftAccessToken: String): MinecraftApi.Ownership {
            ownershipRequests += minecraftAccessToken
            return ownershipHandler(minecraftAccessToken)
        }

        override fun getProfile(minecraftAccessToken: String): MinecraftApi.MinecraftProfile {
            profileRequests += minecraftAccessToken
            return profileHandler(minecraftAccessToken)
        }
    }
}
