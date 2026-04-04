package gg.grounds.keycloak.minecraft.identity

import gg.grounds.keycloak.minecraft.api.MinecraftApi

/**
 * Internal representation of the Minecraft/Xbox identity resolved from upstream Microsoft tokens
 * before it is mapped into Keycloak's brokered context.
 */
data class ResolvedMinecraftIdentity(
    val brokerUserId: String,
    val username: String,
    val loginIdentity: String,
    val ownership: MinecraftApi.Ownership,
    val minecraftJavaUuid: String? = null,
    val minecraftJavaUsername: String? = null,
    val xboxGamertag: String? = null,
    val xboxUserId: String? = null,
)
