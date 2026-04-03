package gg.grounds.keycloak.minecraft.identity

import gg.grounds.keycloak.minecraft.MinecraftIdentityProviderConfig
import gg.grounds.keycloak.minecraft.sync.MinecraftBrokeredAttributes
import org.keycloak.broker.provider.BrokeredIdentityContext

/**
 * Maps a resolved Minecraft/Xbox identity into the brokered context consumed by Keycloak.
 *
 * The resulting context carries the provider-managed user attributes that are later copied to new
 * users and synchronized onto existing users during brokered logins.
 */
class MinecraftIdentityContextFactory(private val config: MinecraftIdentityProviderConfig) {
    fun create(identity: ResolvedMinecraftIdentity): BrokeredIdentityContext =
        BrokeredIdentityContext(identity.brokerUserId, config).apply {
            username = identity.username
            brokerUserId = identity.brokerUserId
            setUserAttribute(
                MinecraftBrokeredAttributes.JAVA_OWNED,
                identity.ownership.ownsJavaEdition.toString(),
            )
            setUserAttribute(
                MinecraftBrokeredAttributes.BEDROCK_OWNED,
                identity.ownership.ownsBedrockEdition.toString(),
            )
            setUserAttribute(MinecraftBrokeredAttributes.LOGIN_IDENTITY, identity.loginIdentity)
            identity.minecraftJavaUuid?.let {
                setUserAttribute(MinecraftBrokeredAttributes.JAVA_UUID, it)
            }
            identity.minecraftJavaUsername?.let {
                setUserAttribute(MinecraftBrokeredAttributes.JAVA_USERNAME, it)
            }
            identity.xboxGamertag?.let {
                setUserAttribute(MinecraftBrokeredAttributes.XBOX_GAMERTAG, it)
            }
            identity.xboxUserId?.let {
                setUserAttribute(MinecraftBrokeredAttributes.XBOX_USER_ID, it)
            }
        }
}
