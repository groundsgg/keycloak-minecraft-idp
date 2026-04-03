package gg.grounds.keycloak.minecraft.identity

import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.exceptions.MinecraftProfileNotFoundException
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthException
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthenticationFailureException
import gg.grounds.keycloak.minecraft.testsupport.FakeMinecraftClient
import gg.grounds.keycloak.minecraft.testsupport.FakeXboxAuthClient
import gg.grounds.keycloak.minecraft.testsupport.xboxResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.keycloak.broker.provider.IdentityBrokerException

class MinecraftIdentityResolverTest {

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

        val identity =
            MinecraftIdentityResolver(xboxAuthClient, minecraftClient).resolve("ms-access-token")

        assertEquals(listOf("ms-access-token"), xboxAuthClient.microsoftAccessTokens)
        assertEquals(listOf("xbox-user-token"), xboxAuthClient.xboxUserTokens)
        assertEquals(listOf("xsts-uhs" to "xsts-token"), minecraftClient.minecraftAuthRequests)
        assertEquals(listOf("minecraft-token"), minecraftClient.ownershipRequests)
        assertEquals(listOf("minecraft-token"), minecraftClient.profileRequests)
        assertEquals("xbox-281467", identity.brokerUserId)
        assertEquals("GroundsSteve", identity.username)
        assertEquals("java", identity.loginIdentity)
        assertEquals("12345678-9012-3456-7890-123456789012", identity.minecraftJavaUuid)
        assertEquals("GroundsSteve", identity.minecraftJavaUsername)
        assertEquals("GroundsTag", identity.xboxGamertag)
        assertEquals("281467", identity.xboxUserId)
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
                    throw MinecraftProfileNotFoundException("The user has no Java profile")
                },
            )

        val identity =
            MinecraftIdentityResolver(xboxAuthClient, minecraftClient).resolve("ms-access-token")

        assertEquals("xboxuhs-xsts-uhs", identity.brokerUserId)
        assertEquals("BedrockTag", identity.username)
        assertEquals("bedrock", identity.loginIdentity)
        assertEquals(null, identity.minecraftJavaUuid)
        assertEquals(null, identity.minecraftJavaUsername)
        assertEquals("BedrockTag", identity.xboxGamertag)
        assertEquals(null, identity.xboxUserId)
    }

    @Test
    fun `rejects accounts without Minecraft entitlement`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    xstsHandler = {
                        xboxResponse(
                            token = "xsts-token",
                            userHash = "xsts-uhs",
                            gamertag = "NoGame",
                        )
                    },
                ),
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
                ),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals(
            "This Microsoft account does not have a Minecraft Java or Bedrock entitlement.",
            exception.message,
        )
    }

    @Test
    fun `reports Java profile setup requirement when Bedrock fallback is unavailable`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    xstsHandler = {
                        xboxResponse(
                            token = "xsts-token",
                            userHash = "xsts-uhs",
                            gamertag = "JavaOnly",
                        )
                    },
                ),
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
                        throw MinecraftProfileNotFoundException("The user has no Java profile")
                    },
                ),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals(
            "Your account has Minecraft Java Edition but no Java profile exists yet. Please log into the Minecraft Launcher once to set up your profile.",
            exception.message,
        )
    }

    @Test
    fun `surfaces Xbox auth errors with provider message`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        throw XboxAuthException(
                            errorCode = 2148916233L,
                            rawMessage = "no-xbox-account",
                            redirectUrl = null,
                        )
                    },
                    xstsHandler = {
                        error("XSTS should not be requested when Xbox authentication fails")
                    },
                ),
                FakeMinecraftClient(
                    authenticateHandler = { _, _ ->
                        error("Minecraft authentication should not be reached")
                    },
                    ownershipHandler = { error("Ownership should not be reached") },
                    profileHandler = { error("Profile should not be reached") },
                ),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals(
            "This Microsoft account has no Xbox account. Please create one at xbox.com/live",
            exception.message,
        )
    }

    @Test
    fun `rejects XSTS responses without a user hash`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    xstsHandler = {
                        xboxResponse(
                            token = "xsts-token",
                            userHash = null,
                            gamertag = "MissingHash",
                        )
                    },
                ),
                FakeMinecraftClient(
                    authenticateHandler = { _, _ ->
                        error("Minecraft authentication should not be reached")
                    },
                    ownershipHandler = { error("Ownership should not be reached") },
                    profileHandler = { error("Profile should not be reached") },
                ),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("XSTS response did not return a user hash", exception.message)
    }

    @Test
    fun `reports Xbox authentication io failures explicitly`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = { throw IOException("upstream Xbox failure") },
                    xstsHandler = { error("XSTS should not be called") },
                ),
                unusedMinecraftClient(),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("Xbox Live authentication failed. Please try again.", exception.message)
        assertTrue(exception.cause is XboxAuthenticationFailureException)
        assertTrue(exception.cause?.cause is IOException)
        assertEquals(
            "authenticate_with_xbox",
            (exception.cause as XboxAuthenticationFailureException).stage,
        )
        assertEquals("upstream Xbox failure", exception.cause?.cause?.message)
    }

    @Test
    fun `reports XSTS io failures explicitly`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    xstsHandler = { throw IOException("upstream XSTS failure") },
                ),
                unusedMinecraftClient(),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("Xbox Live authentication failed. Please try again.", exception.message)
        assertTrue(exception.cause is XboxAuthenticationFailureException)
        assertTrue(exception.cause?.cause is IOException)
        assertEquals(
            "obtain_xsts_token",
            (exception.cause as XboxAuthenticationFailureException).stage,
        )
        assertEquals("upstream XSTS failure", exception.cause?.cause?.message)
    }

    @Test
    fun `keeps Minecraft io failures on Minecraft message`() {
        val resolver =
            MinecraftIdentityResolver(
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
                ),
                FakeMinecraftClient(
                    authenticateHandler = { _, _ ->
                        throw IOException("upstream Minecraft failure")
                    },
                    ownershipHandler = { error("Ownership should not be called") },
                    profileHandler = { error("Profile should not be called") },
                ),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("Minecraft authentication failed. Please try again.", exception.message)
        assertTrue(exception.cause is IOException)
        assertEquals("upstream Minecraft failure", exception.cause?.message)
    }

    @Test
    fun `restores interrupt flag when upstream call is interrupted`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = { throw InterruptedException("request interrupted") },
                    xstsHandler = { error("XSTS should not be called") },
                ),
                unusedMinecraftClient(),
            )

        try {
            val exception =
                assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

            assertEquals("Minecraft authentication was interrupted.", exception.message)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(exception.cause is InterruptedException)
        } finally {
            assertTrue(Thread.interrupted())
            assertFalse(Thread.currentThread().isInterrupted)
        }
    }

    @Test
    fun `rejects Bedrock users without gamertag`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    xstsHandler = {
                        xboxResponse(token = "xsts-token", userHash = "xsts-uhs", gamertag = null)
                    },
                ),
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
                            entitlementNames = setOf("product_minecraft_bedrock"),
                            ownsJavaEdition = false,
                            ownsBedrockEdition = true,
                        )
                    },
                    profileHandler = {
                        error("Profile should not be requested for Bedrock-only flow")
                    },
                ),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("Could not retrieve Xbox Gamertag for Bedrock user", exception.message)
    }

    private fun unusedMinecraftClient(): FakeMinecraftClient =
        FakeMinecraftClient(
            authenticateHandler = { _, _ ->
                error("Minecraft authentication should not be called")
            },
            ownershipHandler = { error("Ownership should not be called") },
            profileHandler = { error("Profile should not be called") },
        )
}
