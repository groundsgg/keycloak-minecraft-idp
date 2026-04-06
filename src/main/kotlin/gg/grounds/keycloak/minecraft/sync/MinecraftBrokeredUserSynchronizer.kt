package gg.grounds.keycloak.minecraft.sync

import gg.grounds.keycloak.minecraft.MinecraftIdentityProviderConfig
import org.keycloak.broker.provider.AbstractIdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.models.IdentityProviderSyncMode
import org.keycloak.models.UserModel

/**
 * Synchronizes provider-managed brokered data onto an existing Keycloak user.
 *
 * Real-name fields respect Keycloak's sync mode when real-name sync is enabled. Managed
 * Minecraft/Xbox attributes are refreshed on every brokered login, and managed attributes missing
 * from the current context are removed so stale values do not linger on the user model.
 */
class MinecraftBrokeredUserSynchronizer(private val config: MinecraftIdentityProviderConfig) {
    fun sync(user: UserModel, context: BrokeredIdentityContext) {
        syncBasicProfile(user, context)
        syncManagedBrokeredAttributes(user, context)
    }

    private fun syncManagedBrokeredAttributes(user: UserModel, context: BrokeredIdentityContext) {
        val brokeredAttributes = context.attributes

        MinecraftBrokeredAttributes.managed(config.syncRealName).forEach { attributeName ->
            val attributeValues = brokeredAttributes[attributeName]
            if (attributeValues.isNullOrEmpty()) {
                user.removeAttribute(attributeName)
            } else {
                user.setAttribute(attributeName, attributeValues)
            }
        }
    }

    private fun syncBasicProfile(user: UserModel, context: BrokeredIdentityContext) {
        if (!config.syncRealName) {
            return
        }
        val authSession = context.authenticationSession ?: return
        val isNewUser =
            authSession.getAuthNote(AbstractIdentityProvider.BROKER_REGISTERED_NEW_USER).toBoolean()
        val shouldSync = isNewUser || config.syncMode == IdentityProviderSyncMode.FORCE

        if (!shouldSync) {
            return
        }

        context.firstName?.takeIf { it != user.firstName }?.let { user.firstName = it }
        context.lastName?.takeIf { it != user.lastName }?.let { user.lastName = it }
    }
}
