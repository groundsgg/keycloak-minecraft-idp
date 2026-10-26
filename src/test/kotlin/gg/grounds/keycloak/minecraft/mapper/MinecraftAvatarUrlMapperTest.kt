package gg.grounds.keycloak.minecraft.mapper

import gg.grounds.keycloak.minecraft.testsupport.RecordingUserState
import gg.grounds.keycloak.minecraft.testsupport.recordingUserModel
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.keycloak.models.ProtocolMapperModel
import org.keycloak.models.UserModel
import org.keycloak.models.UserSessionModel
import org.keycloak.representations.AccessToken

class MinecraftAvatarUrlMapperTest {

    @Test
    fun `lists the template setting among its config properties`() {
        // Keycloak calls this for /admin/serverinfo and the admin console.
        val names = MinecraftAvatarUrlMapper().configProperties.map { it.name }

        assertTrue(MinecraftAvatarUrlMapper.URL_TEMPLATE in names)
    }

    @Test
    fun `maps the avatar url built from the java uuid`() {
        val token = AccessToken()

        MinecraftAvatarUrlMapper()
            .transformUserInfoToken(
                token,
                mapperModel("https://avatars.grounds.gg/head/{uuid}?size=128"),
                null,
                userSession(javaUuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                null,
            )

        assertEquals(
            "https://avatars.grounds.gg/head/069a79f4-44e9-4726-a5be-fca90e38aaf5?size=128",
            token.otherClaims["picture"],
        )
    }

    @Test
    fun `omits the claim when the user has no java uuid`() {
        val token = AccessToken()

        MinecraftAvatarUrlMapper()
            .transformUserInfoToken(
                token,
                mapperModel("https://avatars.grounds.gg/head/{uuid}?size=128"),
                null,
                userSession(javaUuid = null),
                null,
            )

        assertFalse(token.otherClaims.containsKey("picture"))
    }

    @Test
    fun `omits the claim when no template is configured`() {
        val token = AccessToken()

        MinecraftAvatarUrlMapper()
            .transformUserInfoToken(
                token,
                mapperModel(template = null),
                null,
                userSession(javaUuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                null,
            )

        assertFalse(token.otherClaims.containsKey("picture"))
    }

    private fun mapperModel(template: String?): ProtocolMapperModel =
        ProtocolMapperModel().apply {
            name = "minecraft-avatar"
            protocol = "openid-connect"
            protocolMapper = MinecraftAvatarUrlMapper.PROVIDER_ID
            config =
                mutableMapOf(
                        "claim.name" to "picture",
                        "jsonType.label" to "String",
                        "userinfo.token.claim" to "true",
                    )
                    .apply { template?.let { put(MinecraftAvatarUrlMapper.URL_TEMPLATE, it) } }
        }

    private fun userSession(javaUuid: String?): UserSessionModel {
        val user: UserModel =
            recordingUserModel(
                RecordingUserState(username = "GroundsSteve").apply {
                    javaUuid?.let { attributes["minecraft_java_uuid"] = mutableListOf(it) }
                },
            )
        return Proxy.newProxyInstance(
            UserSessionModel::class.java.classLoader,
            arrayOf(UserSessionModel::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUser" -> user
                "toString" -> "TestUserSession"
                "hashCode" -> 0
                "equals" -> false
                else ->
                    throw UnsupportedOperationException(
                        "Unexpected UserSessionModel method invoked during test (method=${method.name})",
                    )
            }
        } as UserSessionModel
    }
}
