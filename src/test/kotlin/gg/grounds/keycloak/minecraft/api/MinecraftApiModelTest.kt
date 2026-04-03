package gg.grounds.keycloak.minecraft.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftApiModelTest {

    @Test
    fun `resolveOwnership detects Java entitlement`() {
        val ownership = MinecraftApi.resolveOwnership(setOf("game_minecraft"))

        assertEquals(setOf("game_minecraft"), ownership.entitlementNames)
        assertTrue(ownership.ownsJavaEdition)
        assertFalse(ownership.ownsBedrockEdition)
    }

    @Test
    fun `resolveOwnership detects Bedrock entitlement`() {
        val ownership = MinecraftApi.resolveOwnership(setOf("product_minecraft_bedrock"))

        assertFalse(ownership.ownsJavaEdition)
        assertTrue(ownership.ownsBedrockEdition)
    }

    @Test
    fun `resolveOwnership detects both editions`() {
        val ownership =
            MinecraftApi.resolveOwnership(setOf("product_minecraft", "game_minecraft_bedrock"))

        assertTrue(ownership.ownsJavaEdition)
        assertTrue(ownership.ownsBedrockEdition)
    }

    @Test
    fun `formattedUuid adds hyphens to compact uuid`() {
        val profile =
            MinecraftApi.MinecraftProfile(
                id = "12345678901234567890123456789012",
                name = "GroundsSteve",
                skins = null,
                capes = null,
            )

        assertEquals("12345678-9012-3456-7890-123456789012", profile.formattedUuid)
    }

    @Test
    fun `formattedUuid keeps non compact ids unchanged`() {
        val profile =
            MinecraftApi.MinecraftProfile(
                id = "12345678-9012-3456-7890-123456789012",
                name = "GroundsSteve",
                skins = null,
                capes = null,
            )

        assertEquals("12345678-9012-3456-7890-123456789012", profile.formattedUuid)
    }
}
