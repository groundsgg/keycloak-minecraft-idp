package gg.grounds.keycloak.minecraft.identity

import gg.grounds.keycloak.minecraft.MinecraftIdentityProviderConfig
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinecraftIdentityContextFactoryTest {

    private val factory =
        MinecraftIdentityContextFactory(
            MinecraftIdentityProviderConfig().apply {
                isEnabled = true
                clientId = "minecraft-client-id"
                clientSecret = "minecraft-client-secret"
            }
        )

    @Test
    fun `creates context for Java identity`() {
        val context =
            factory.create(
                ResolvedMinecraftIdentity(
                    brokerUserId = "xboxptx-partner-123",
                    username = "GroundsSteve",
                    loginIdentity = "java",
                    ownership =
                        MinecraftApi.Ownership(
                            entitlementNames = setOf("product_minecraft"),
                            ownsJavaEdition = true,
                            ownsBedrockEdition = false,
                        ),
                    minecraftJavaUuid = "12345678-9012-3456-7890-123456789012",
                    minecraftJavaUsername = "GroundsSteve",
                    xboxGamertag = "GroundsTag",
                )
            )

        assertEquals("xboxptx-partner-123", context.id)
        assertEquals("xboxptx-partner-123", context.brokerUserId)
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
    }

    @Test
    fun `omits absent optional attributes for Bedrock identity`() {
        val context =
            factory.create(
                ResolvedMinecraftIdentity(
                    brokerUserId = "xboxptx-partner-123",
                    username = "BedrockTag",
                    loginIdentity = "bedrock",
                    ownership =
                        MinecraftApi.Ownership(
                            entitlementNames = setOf("product_minecraft_bedrock"),
                            ownsJavaEdition = false,
                            ownsBedrockEdition = true,
                        ),
                    xboxGamertag = "BedrockTag",
                )
            )

        assertEquals("xboxptx-partner-123", context.id)
        assertEquals("bedrocktag", context.username)
        assertEquals("bedrock", context.getUserAttribute("minecraft_login_identity"))
        assertEquals("false", context.getUserAttribute("minecraft_java_owned"))
        assertEquals("true", context.getUserAttribute("minecraft_bedrock_owned"))
        assertEquals("BedrockTag", context.getUserAttribute("xbox_gamertag"))
        assertNull(context.getUserAttribute("minecraft_java_uuid"))
        assertNull(context.getUserAttribute("minecraft_java_username"))
    }
}
