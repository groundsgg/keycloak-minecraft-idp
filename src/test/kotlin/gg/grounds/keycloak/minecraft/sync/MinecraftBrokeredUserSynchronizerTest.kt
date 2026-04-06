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
            firstName = "Avery"
            lastName = "Stone"
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals("Avery", state.firstName)
        assertEquals("Stone", state.lastName)
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
            firstName = "Avery"
            lastName = "Stone"
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals(null, state.firstName)
        assertEquals(null, state.lastName)
    }

    @Test
    fun `keeps existing names when sync mode is import`() {
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
            firstName = "Avery"
            lastName = "Stone"
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals("Existing", state.firstName)
        assertEquals("Player", state.lastName)
    }

    @Test
    fun `refreshes existing names when sync mode is force`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply {
                    isEnabled = true
                    syncRealName = true
                    syncMode = IdentityProviderSyncMode.FORCE
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
            firstName = "Avery"
            lastName = "Stone"
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals("Avery", state.firstName)
        assertEquals("Stone", state.lastName)
    }

    @Test
    fun `syncs managed Minecraft attributes for existing users`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply {
                    isEnabled = true
                    syncRealName = true
                }
            )
        val state = RecordingUserState(username = "minecraft-user")
        val context = brokeredContext {
            authenticationSession = authenticationSession(isNewUser = false)
            setUserAttribute("microsoft_name", "Avery Stone")
            setUserAttribute("minecraft_login_identity", "java")
            setUserAttribute("minecraft_java_owned", "true")
            setUserAttribute("minecraft_bedrock_owned", "false")
            setUserAttribute("minecraft_java_uuid", "12345678-9012-3456-7890-123456789012")
            setUserAttribute("minecraft_java_username", "GroundsSteve")
            setUserAttribute("xbox_gamertag", "GroundsTag")
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals(listOf("Avery Stone"), state.attributes["microsoft_name"]?.toList())
        assertEquals(listOf("java"), state.attributes["minecraft_login_identity"]?.toList())
        assertEquals(listOf("true"), state.attributes["minecraft_java_owned"]?.toList())
        assertEquals(listOf("false"), state.attributes["minecraft_bedrock_owned"]?.toList())
        assertEquals(
            listOf("12345678-9012-3456-7890-123456789012"),
            state.attributes["minecraft_java_uuid"]?.toList(),
        )
        assertEquals(listOf("GroundsSteve"), state.attributes["minecraft_java_username"]?.toList())
        assertEquals(listOf("GroundsTag"), state.attributes["xbox_gamertag"]?.toList())
    }

    @Test
    fun `removes stale managed Minecraft attributes when context omits them`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply {
                    isEnabled = true
                    syncRealName = true
                }
            )
        val state =
            RecordingUserState(
                username = "minecraft-user",
                attributes =
                    mutableMapOf(
                        "microsoft_name" to mutableListOf("Old Name"),
                        "minecraft_login_identity" to mutableListOf("java"),
                        "minecraft_java_owned" to mutableListOf("true"),
                        "minecraft_bedrock_owned" to mutableListOf("true"),
                        "minecraft_java_uuid" to
                            mutableListOf("12345678-9012-3456-7890-123456789012"),
                        "minecraft_java_username" to mutableListOf("GroundsSteve"),
                        "xbox_gamertag" to mutableListOf("GroundsTag"),
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

        assertEquals(null, state.attributes["microsoft_name"])
        assertEquals(listOf("bedrock"), state.attributes["minecraft_login_identity"]?.toList())
        assertEquals(listOf("false"), state.attributes["minecraft_java_owned"]?.toList())
        assertEquals(listOf("true"), state.attributes["minecraft_bedrock_owned"]?.toList())
        assertEquals(listOf("BedrockTag"), state.attributes["xbox_gamertag"]?.toList())
        assertEquals(null, state.attributes["minecraft_java_uuid"])
        assertEquals(null, state.attributes["minecraft_java_username"])
        assertEquals(listOf("preserved"), state.attributes["custom_attribute"]?.toList())
    }

    @Test
    fun `preserves microsoft name when real name sync is disabled`() {
        val synchronizer =
            MinecraftBrokeredUserSynchronizer(
                MinecraftIdentityProviderConfig().apply { isEnabled = true }
            )
        val state =
            RecordingUserState(
                username = "minecraft-user",
                attributes =
                    mutableMapOf(
                        "microsoft_name" to mutableListOf("Old Name"),
                        "minecraft_login_identity" to mutableListOf("java"),
                    ),
            )
        val context = brokeredContext {
            authenticationSession = authenticationSession(isNewUser = false)
            setUserAttribute("minecraft_login_identity", "bedrock")
        }

        synchronizer.sync(recordingUserModel(state), context)

        assertEquals(listOf("Old Name"), state.attributes["microsoft_name"]?.toList())
        assertEquals(listOf("bedrock"), state.attributes["minecraft_login_identity"]?.toList())
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
