package gg.grounds.keycloak.minecraft.mapper

import gg.grounds.keycloak.minecraft.sync.MinecraftBrokeredAttributes
import org.keycloak.models.ProtocolMapperModel
import org.keycloak.models.UserSessionModel
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.representations.IDToken

/**
 * OIDC protocol mapper that emits the player's avatar URL, built from a configured template and the
 * user's `minecraft_java_uuid` attribute.
 *
 * Keycloak's built-in mappers cannot template a value, so a client that wants an avatar URL (for
 * example the `picture` claim) would otherwise need the URL stored on every user. Building it at
 * token time keeps nothing extra on the user and lets the URL change without a re-login. Users
 * without a Java UUID (Bedrock-only logins) get no claim.
 */
class MinecraftAvatarUrlMapper :
    AbstractOIDCProtocolMapper(), OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    override fun getId(): String = PROVIDER_ID

    override fun getDisplayType(): String = "Minecraft Avatar URL"

    override fun getDisplayCategory(): String = TOKEN_MAPPER_CATEGORY

    override fun getHelpText(): String =
        "Maps the player's avatar URL to a token claim. `$UUID_PLACEHOLDER` in the template is " +
            "replaced with the user's Minecraft Java UUID; users without one get no claim."

    // Not `configProperties`: inside the class that name resolves to this very getter (Kotlin's
    // synthetic property for getConfigProperties()), and the method calls itself until the stack
    // overflows, which takes /admin/serverinfo down with it.
    override fun getConfigProperties(): List<ProviderConfigProperty> = CONFIG_PROPERTIES

    override fun setClaim(
        token: IDToken,
        mappingModel: ProtocolMapperModel,
        userSession: UserSessionModel,
    ) {
        val template = mappingModel.config[URL_TEMPLATE]?.takeIf { it.isNotBlank() } ?: return
        val uuid =
            userSession.user.getFirstAttribute(MinecraftBrokeredAttributes.JAVA_UUID) ?: return
        OIDCAttributeMapperHelper.mapClaim(
            token,
            mappingModel,
            template.replace(UUID_PLACEHOLDER, uuid),
        )
    }

    companion object {
        const val PROVIDER_ID = "oidc-minecraft-avatar-url-mapper"
        const val URL_TEMPLATE = "avatar.url.template"
        const val UUID_PLACEHOLDER = "{uuid}"

        private val CONFIG_PROPERTIES =
            mutableListOf(
                    ProviderConfigProperty().apply {
                        name = URL_TEMPLATE
                        label = "Avatar URL template"
                        helpText =
                            "Avatar URL with `$UUID_PLACEHOLDER` where the Minecraft Java UUID goes, " +
                                "e.g. https://avatars.example/head/$UUID_PLACEHOLDER?size=128."
                        type = ProviderConfigProperty.STRING_TYPE
                    },
                )
                .also {
                    OIDCAttributeMapperHelper.addTokenClaimNameConfig(it)
                    OIDCAttributeMapperHelper.addJsonTypeConfig(it)
                    OIDCAttributeMapperHelper.addIncludeInTokensConfig(
                        it,
                        MinecraftAvatarUrlMapper::class.java,
                    )
                }
    }
}
