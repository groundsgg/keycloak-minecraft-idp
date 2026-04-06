package gg.grounds.keycloak.minecraft.identity

import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.exceptions.MinecraftProfileNotFoundException
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthException
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthenticationFailureException
import gg.grounds.keycloak.minecraft.testsupport.FakeMinecraftClient
import gg.grounds.keycloak.minecraft.testsupport.FakeXboxAuthClient
import gg.grounds.keycloak.minecraft.testsupport.encryptedPartnerXstsToken
import gg.grounds.keycloak.minecraft.testsupport.generateRsaKeyPair
import gg.grounds.keycloak.minecraft.testsupport.toPkcs1Pem
import gg.grounds.keycloak.minecraft.testsupport.toPkcs8Pem
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
                minecraftXstsHandler = {
                    xboxResponse(
                        token = "xsts-token",
                        userHash = "xsts-uhs",
                        gamertag = "GroundsTag",
                    )
                },
                partnerXstsHandler = { _, relyingParty ->
                    assertEquals("https://grounds.example.com", relyingParty)
                    xboxResponse(
                        token = "partner-xsts-token",
                        userHash = null,
                        gamertag = "PartnerTag",
                        partnerXboxUserId = "partner-281467",
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
            MinecraftIdentityResolver(
                    xboxAuthClient,
                    minecraftClient,
                    "https://grounds.example.com",
                )
                .resolve("ms-access-token")

        assertEquals(listOf("ms-access-token"), xboxAuthClient.microsoftAccessTokens)
        assertEquals(listOf("xbox-user-token"), xboxAuthClient.minecraftXstsUserTokens)
        assertEquals(
            listOf("xbox-user-token" to "https://grounds.example.com"),
            xboxAuthClient.partnerXstsRequests,
        )
        assertEquals(listOf("xsts-uhs" to "xsts-token"), minecraftClient.minecraftAuthRequests)
        assertEquals(listOf("minecraft-token"), minecraftClient.ownershipRequests)
        assertEquals(listOf("minecraft-token"), minecraftClient.profileRequests)
        assertEquals("xboxptx-partner-281467", identity.brokerUserId)
        assertEquals("GroundsSteve", identity.username)
        assertEquals("java", identity.loginIdentity)
        assertEquals("12345678-9012-3456-7890-123456789012", identity.minecraftJavaUuid)
        assertEquals("GroundsSteve", identity.minecraftJavaUsername)
        assertEquals("GroundsTag", identity.xboxGamertag)
    }

    @Test
    fun `falls back to Bedrock identity when Java profile is missing`() {
        val xboxAuthClient =
            FakeXboxAuthClient(
                authenticateHandler = {
                    xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                },
                minecraftXstsHandler = {
                    xboxResponse(
                        token = "xsts-token",
                        userHash = "xsts-uhs",
                        gamertag = "BedrockTag",
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
            MinecraftIdentityResolver(
                    xboxAuthClient,
                    minecraftClient,
                    "https://grounds.example.com",
                )
                .resolve("ms-access-token")

        assertEquals("xboxptx-partner-123", identity.brokerUserId)
        assertEquals("BedrockTag", identity.username)
        assertEquals("bedrock", identity.loginIdentity)
        assertEquals(null, identity.minecraftJavaUuid)
        assertEquals(null, identity.minecraftJavaUsername)
        assertEquals("BedrockTag", identity.xboxGamertag)
    }

    @Test
    fun `rejects accounts without Minecraft entitlement`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
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
                "https://grounds.example.com",
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
                    minecraftXstsHandler = {
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
                "https://grounds.example.com",
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
                    minecraftXstsHandler = {
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
                "https://grounds.example.com",
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
                    minecraftXstsHandler = {
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
                "https://grounds.example.com",
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
                    minecraftXstsHandler = { error("XSTS should not be called") },
                ),
                unusedMinecraftClient(),
                "https://grounds.example.com",
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
                    minecraftXstsHandler = { throw IOException("upstream XSTS failure") },
                ),
                unusedMinecraftClient(),
                "https://grounds.example.com",
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("Xbox Live authentication failed. Please try again.", exception.message)
        assertTrue(exception.cause is XboxAuthenticationFailureException)
        assertTrue(exception.cause?.cause is IOException)
        assertEquals(
            "obtain_minecraft_xsts_token",
            (exception.cause as XboxAuthenticationFailureException).stage,
        )
        assertEquals("upstream XSTS failure", exception.cause?.cause?.message)
    }

    @Test
    fun `reports partner XSTS io failures explicitly`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(token = "xsts-token", userHash = "xsts-uhs")
                    },
                    partnerXstsHandler = { _, _ ->
                        throw IOException("upstream partner XSTS failure")
                    },
                ),
                unusedMinecraftClient(),
                "https://grounds.example.com",
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("Xbox Live authentication failed. Please try again.", exception.message)
        assertTrue(exception.cause is XboxAuthenticationFailureException)
        assertTrue(exception.cause?.cause is IOException)
        assertEquals(
            "obtain_partner_xsts_token",
            (exception.cause as XboxAuthenticationFailureException).stage,
        )
        assertEquals("upstream partner XSTS failure", exception.cause?.cause?.message)
    }

    @Test
    fun `keeps Minecraft io failures on Minecraft message`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
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
                "https://grounds.example.com",
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
                    minecraftXstsHandler = { error("XSTS should not be called") },
                ),
                unusedMinecraftClient(),
                "https://grounds.example.com",
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
                    minecraftXstsHandler = {
                        xboxResponse(token = "xsts-token", userHash = "xsts-uhs", gamertag = null)
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = "partner-xsts-token",
                            userHash = null,
                            gamertag = null,
                            partnerXboxUserId = "partner-123",
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
                            entitlementNames = setOf("product_minecraft_bedrock"),
                            ownsJavaEdition = false,
                            ownsBedrockEdition = true,
                        )
                    },
                    profileHandler = {
                        error("Profile should not be requested for Bedrock-only flow")
                    },
                ),
                "https://grounds.example.com",
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals("Could not retrieve Xbox Gamertag for Bedrock user", exception.message)
    }

    @Test
    fun `rejects partner XSTS responses without ptx`() {
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(
                            token = "xsts-token",
                            userHash = "xsts-uhs",
                            gamertag = "GroundsTag",
                        )
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = "partner-xsts-token",
                            userHash = null,
                            gamertag = "PartnerTag",
                        )
                    },
                ),
                unusedMinecraftClient(),
                "https://grounds.example.com",
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals(
            "Xbox partner token did not return ptx claim. Verify the partner relying party configuration or configure `partnerXstsPrivateKey` for encrypted partner tokens.",
            exception.message,
        )
    }

    @Test
    fun `keeps direct partner ptx when configured decryption fails`() {
        val encryptionKeyPair = generateRsaKeyPair()
        val decryptionKeyPair = generateRsaKeyPair()
        val encryptedPartnerToken =
            encryptedPartnerXstsToken(
                encryptionKeyPair,
                """{"xui":[{"gtg":"EncryptedTag","ptx":"partner-encrypted"}],"iss":"xsts"}""",
            )
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(
                            token = "xsts-token",
                            userHash = "xsts-uhs",
                            gamertag = "GroundsTag",
                        )
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = encryptedPartnerToken,
                            userHash = null,
                            gamertag = "PartnerTag",
                            partnerXboxUserId = "partner-direct",
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
                        MinecraftApi.MinecraftProfile(
                            id = "12345678901234567890123456789012",
                            name = "GroundsSteve",
                            skins = null,
                            capes = null,
                        )
                    },
                ),
                "https://grounds.example.com",
                PartnerXstsTokenInspector.fromPemReference(toPkcs8Pem(decryptionKeyPair)),
            )

        val identity = resolver.resolve("ms-access-token")

        assertEquals("xboxptx-partner-direct", identity.brokerUserId)
        assertEquals("GroundsSteve", identity.username)
        assertEquals("GroundsTag", identity.xboxGamertag)
    }

    @Test
    fun `keeps decryption failure when partner token header is malformed`() {
        val keyPair = generateRsaKeyPair()
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(
                            token = "xsts-token",
                            userHash = "xsts-uhs",
                            gamertag = "GroundsTag",
                        )
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = "%%%.payload.part3.part4.part5",
                            userHash = null,
                            gamertag = "GroundsTag",
                            partnerXboxUserId = null,
                        )
                    },
                ),
                unusedMinecraftClient(),
                "https://grounds.example.com",
                PartnerXstsTokenInspector.fromPemReference(toPkcs8Pem(keyPair)),
            )

        val exception =
            assertFailsWith<IdentityBrokerException> { resolver.resolve("ms-access-token") }

        assertEquals(
            "Xbox partner token decryption failed. Verify `partnerXstsPrivateKey` for the configured relying party.",
            exception.message,
        )
    }

    @Test
    fun `resolves partner ptx from encrypted token when display claims omit it`() {
        val keyPair = generateRsaKeyPair()
        val encryptedPartnerToken =
            encryptedPartnerXstsToken(
                keyPair,
                """{"xui":[{"gtg":"GroundsTag","ptx":"partner-987"}],"iss":"xsts"}""",
            )
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(
                            token = "xsts-token",
                            userHash = "xsts-uhs",
                            gamertag = "GroundsTag",
                        )
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = encryptedPartnerToken,
                            userHash = null,
                            gamertag = "GroundsTag",
                            partnerXboxUserId = null,
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
                        MinecraftApi.MinecraftProfile(
                            id = "12345678901234567890123456789012",
                            name = "GroundsSteve",
                            skins = null,
                            capes = null,
                        )
                    },
                ),
                "rp://grounds.gg/",
                PartnerXstsTokenInspector.fromPemReference(toPkcs8Pem(keyPair)),
            )

        val identity = resolver.resolve("ms-access-token")

        assertEquals("xboxptx-partner-987", identity.brokerUserId)
        assertEquals("GroundsSteve", identity.username)
        assertEquals("GroundsTag", identity.xboxGamertag)
    }

    @Test
    fun `resolves gamertag from encrypted partner token when display claims omit it`() {
        val keyPair = generateRsaKeyPair()
        val encryptedPartnerToken =
            encryptedPartnerXstsToken(
                keyPair,
                """{"xui":[{"gtg":"EncryptedTag","ptx":"partner-654"}],"iss":"xsts"}""",
            )
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(token = "xsts-token", userHash = "xsts-uhs", gamertag = null)
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = encryptedPartnerToken,
                            userHash = null,
                            gamertag = null,
                            partnerXboxUserId = null,
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
                        MinecraftApi.MinecraftProfile(
                            id = "12345678901234567890123456789012",
                            name = "GroundsSteve",
                            skins = null,
                            capes = null,
                        )
                    },
                ),
                "rp://grounds.gg/",
                PartnerXstsTokenInspector.fromPemReference(toPkcs8Pem(keyPair)),
            )

        val identity = resolver.resolve("ms-access-token")

        assertEquals("EncryptedTag", identity.xboxGamertag)
    }

    @Test
    fun `resolves encrypted partner token with PKCS1 private key`() {
        val keyPair = generateRsaKeyPair()
        val encryptedPartnerToken =
            encryptedPartnerXstsToken(
                keyPair,
                """{"xui":[{"gtg":"Pkcs1Tag","ptx":"partner-pkcs1"}],"iss":"xsts"}""",
            )
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(token = "xsts-token", userHash = "xsts-uhs", gamertag = null)
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = encryptedPartnerToken,
                            userHash = null,
                            gamertag = null,
                            partnerXboxUserId = null,
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
                        MinecraftApi.MinecraftProfile(
                            id = "12345678901234567890123456789012",
                            name = "GroundsSteve",
                            skins = null,
                            capes = null,
                        )
                    },
                ),
                "rp://grounds.gg/",
                PartnerXstsTokenInspector.fromPemReference(toPkcs1Pem(keyPair)),
            )

        val identity = resolver.resolve("ms-access-token")

        assertEquals("xboxptx-partner-pkcs1", identity.brokerUserId)
        assertEquals("Pkcs1Tag", identity.xboxGamertag)
    }

    @Test
    fun `resolves encrypted partner token with single-field pasted PKCS8 key`() {
        val keyPair = generateRsaKeyPair()
        val encryptedPartnerToken =
            encryptedPartnerXstsToken(
                keyPair,
                """{"xui":[{"gtg":"PastedTag","ptx":"partner-pasted"}],"iss":"xsts"}""",
            )
        val pastedPem = toPkcs8Pem(keyPair).replace("\n", " ")
        val resolver =
            MinecraftIdentityResolver(
                FakeXboxAuthClient(
                    authenticateHandler = {
                        xboxResponse(token = "xbox-user-token", userHash = "xbox-uhs")
                    },
                    minecraftXstsHandler = {
                        xboxResponse(token = "xsts-token", userHash = "xsts-uhs", gamertag = null)
                    },
                    partnerXstsHandler = { _, _ ->
                        xboxResponse(
                            token = encryptedPartnerToken,
                            userHash = null,
                            gamertag = null,
                            partnerXboxUserId = null,
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
                        MinecraftApi.MinecraftProfile(
                            id = "12345678901234567890123456789012",
                            name = "GroundsSteve",
                            skins = null,
                            capes = null,
                        )
                    },
                ),
                "rp://grounds.gg/",
                PartnerXstsTokenInspector.fromPemReference(pastedPem),
            )

        val identity = resolver.resolve("ms-access-token")

        assertEquals("xboxptx-partner-pasted", identity.brokerUserId)
        assertEquals("PastedTag", identity.xboxGamertag)
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
