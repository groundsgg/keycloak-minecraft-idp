package gg.grounds.keycloak.minecraft.sync

/** Attribute keys managed by this identity provider on brokered Keycloak users. */
object MinecraftBrokeredAttributes {
    const val LOGIN_IDENTITY = "minecraft_login_identity"
    const val JAVA_OWNED = "minecraft_java_owned"
    const val BEDROCK_OWNED = "minecraft_bedrock_owned"
    const val JAVA_UUID = "minecraft_java_uuid"
    const val JAVA_USERNAME = "minecraft_java_username"
    const val XBOX_GAMERTAG = "xbox_gamertag"

    val managed =
        setOf(LOGIN_IDENTITY, JAVA_OWNED, BEDROCK_OWNED, JAVA_UUID, JAVA_USERNAME, XBOX_GAMERTAG)
}
