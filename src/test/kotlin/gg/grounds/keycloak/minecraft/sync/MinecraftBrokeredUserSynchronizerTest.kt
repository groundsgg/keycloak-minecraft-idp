package gg.grounds.keycloak.minecraft.sync

import gg.grounds.keycloak.minecraft.MinecraftIdentityProviderConfig
import gg.grounds.keycloak.minecraft.testsupport.RecordingUserState
import gg.grounds.keycloak.minecraft.testsupport.authenticationSession
import gg.grounds.keycloak.minecraft.testsupport.recordingUserModel
import kotlin.test.Test
import kotlin.test.assertEquals
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.models.IdentityProviderSyncMode

class MinecraftBrokeredUserSynchronizerTest {

    @Test
    fun `syncs first and last name for new users`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply {
                    isEnabled = true
                    syncRealName = true
                }
            )
        val state = RecordingUserState(username = "minecraft-user")
        val context = brokeredContext {
            authenticationSession = authenticationSession(isNewUser = true)
            firstName = "Lukas"
            lastName = "Jost"
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals("Lukas", state.firstName)
        assertEquals("Jost", state.lastName)
    }

    @Test
    fun `ignores names when real name sync is disabled`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply { isEnabled = true }
            )
        val state = RecordingUserState(username = "minecraft-user")
        val context = brokeredContext {
            authenticationSession = authenticationSession(isNewUser = true)
            firstName = "Lukas"
            lastName = "Jost"
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals(null, state.firstName)
        assertEquals(null, state.lastName)
    }

    @Test
    fun `keeps existing names when sync mode is not force`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply {
                    isEnabled = true
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
        val context = brokeredContext {
            authenticationSession = authenticationSession(isNewUser = false)
            firstName = "Lukas"
            lastName = "Jost"
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals("Existing", state.firstName)
        assertEquals("Player", state.lastName)
    }

    @Test
    fun `syncs managed Minecraft attributes for existing users`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply { isEnabled = true }
            )
        val state = RecordingUserState(username = "minecraft-user")
        val context = brokeredContext {
            authenticationSession = authenticationSession(isNewUser = false)
            setUserAttribute("minecraft_login_identity", "java")
            setUserAttribute("minecraft_java_owned", "true")
            setUserAttribute("minecraft_bedrock_owned", "false")
            setUserAttribute("minecraft_java_uuid", "12345678-9012-3456-7890-123456789012")
            setUserAttribute("minecraft_java_username", "GroundsSteve")
            setUserAttribute("xbox_gamertag", "GroundsTag")
            setUserAttribute("xbox_user_id", "281467")
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals(listOf("java"), state.attributes["minecraft_login_identity"]?.toList())
        assertEquals(listOf("true"), state.attributes["minecraft_java_owned"]?.toList())
        assertEquals(listOf("false"), state.attributes["minecraft_bedrock_owned"]?.toList())
        assertEquals(
            listOf("12345678-9012-3456-7890-123456789012"),
            state.attributes["minecraft_java_uuid"]?.toList(),
        )
        assertEquals(listOf("GroundsSteve"), state.attributes["minecraft_java_username"]?.toList())
        assertEquals(listOf("GroundsTag"), state.attributes["xbox_gamertag"]?.toList())
        assertEquals(listOf("281467"), state.attributes["xbox_user_id"]?.toList())
    }

    @Test
    fun `removes stale managed Minecraft attributes when context omits them`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply { isEnabled = true }
            )
        val state =
            RecordingUserState(
                username = "minecraft-user",
                attributes =
                    mutableMapOf(
                        "minecraft_login_identity" to mutableListOf("java"),
                        "minecraft_java_owned" to mutableListOf("true"),
                        "minecraft_bedrock_owned" to mutableListOf("true"),
                        "minecraft_java_uuid" to
                            mutableListOf("12345678-9012-3456-7890-123456789012"),
                        "minecraft_java_username" to mutableListOf("GroundsSteve"),
                        "xbox_gamertag" to mutableListOf("GroundsTag"),
                        "xbox_user_id" to mutableListOf("281467"),
                        "custom_attribute" to mutableListOf("preserved"),
                    ),
            )
        val context = brokeredContext {
            authenticationSession = authenticationSession(isNewUser = false)
            setUserAttribute("minecraft_login_identity", "bedrock")
            setUserAttribute("minecraft_java_owned", "false")
            setUserAttribute("minecraft_bedrock_owned", "true")
            setUserAttribute("xbox_gamertag", "BedrockTag")
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals(listOf("bedrock"), state.attributes["minecraft_login_identity"]?.toList())
        assertEquals(listOf("false"), state.attributes["minecraft_java_owned"]?.toList())
        assertEquals(listOf("true"), state.attributes["minecraft_bedrock_owned"]?.toList())
        assertEquals(listOf("BedrockTag"), state.attributes["xbox_gamertag"]?.toList())
        assertEquals(null, state.attributes["minecraft_java_uuid"])
        assertEquals(null, state.attributes["minecraft_java_username"])
        assertEquals(null, state.attributes["xbox_user_id"])
        assertEquals(listOf("preserved"), state.attributes["custom_attribute"]?.toList())
    }

    private fun brokeredContext(
        block: BrokeredIdentityContext.() -> Unit
    ): BrokeredIdentityContext =
        BrokeredIdentityContext(
                "minecraft-id",
                MinecraftIdentityProviderConfig().apply {
                    isEnabled = true
                    clientId = "minecraft-client-id"
                    clientSecret = "minecraft-client-secret"
                },
            )
            .apply(block)
}
