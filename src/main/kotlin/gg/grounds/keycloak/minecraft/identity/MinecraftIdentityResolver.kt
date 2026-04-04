package gg.grounds.keycloak.minecraft.identity

import gg.grounds.keycloak.minecraft.MinecraftIdentityProvider
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.MinecraftClient
import gg.grounds.keycloak.minecraft.api.XboxAuthClient
import gg.grounds.keycloak.minecraft.api.exceptions.MinecraftProfileNotFoundException
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthException
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthenticationFailureException
import java.io.IOException
import org.jboss.logging.Logger
import org.keycloak.broker.provider.IdentityBrokerException

/**
 * Resolves a Microsoft access token into the provider's internal Minecraft/Xbox identity model.
 *
 * The resolver handles the full upstream sequence:
 * 1. Xbox user authentication
 * 2. XSTS token exchange
 * 3. Minecraft service authentication
 * 4. Entitlement lookup
 * 5. Java profile lookup when Java ownership is present
 *
 * If Java ownership exists but the Java profile has not been created yet, the resolver falls back
 * to Bedrock identity when Bedrock ownership is also present. Otherwise, it surfaces the launcher
 * setup requirement to the user.
 *
 * Transport failures are translated into user-facing broker errors with provider-specific messages,
 * while known Xbox protocol errors retain their domain-specific explanations.
 */
class MinecraftIdentityResolver(
    private val xboxAuthClient: XboxAuthClient = gg.grounds.keycloak.minecraft.api.XboxAuthApi(),
    private val minecraftClient: MinecraftClient = MinecraftApi(),
) {
    fun resolve(accessToken: String): ResolvedMinecraftIdentity {
        try {
            val xboxResponse =
                try {
                    xboxAuthClient.authenticateWithXbox(accessToken)
                } catch (e: XboxAuthException) {
                    throw e
                } catch (e: IOException) {
                    throw XboxAuthenticationFailureException("authenticate_with_xbox", e)
                }

            val xstsResponse =
                try {
                    xboxAuthClient.obtainXstsToken(xboxResponse.token)
                } catch (e: XboxAuthException) {
                    logger.warnf(
                        e,
                        "Requested XSTS token failed (provider=%s, xerr=%d, reason=%s, rawMessage=%s, redirect=%s)",
                        MinecraftIdentityProvider.PROVIDER_ID,
                        e.errorCode,
                        e.message ?: e.javaClass.simpleName,
                        e.rawMessage,
                        e.redirectUrl,
                    )
                    throw IdentityBrokerException(e.message, e)
                } catch (e: IOException) {
                    throw XboxAuthenticationFailureException("obtain_xsts_token", e)
                }

            val xboxGamertag = xstsResponse.gamertag
            val userHash =
                xstsResponse.userHash
                    ?: throw IdentityBrokerException("XSTS response did not return a user hash")
            val stableBrokerUserId = resolveStableBrokerUserId(userHash)

            val minecraftToken =
                minecraftClient.authenticateWithMinecraft(userHash, xstsResponse.token).accessToken
            val ownership = minecraftClient.getOwnership(minecraftToken)

            if (ownership.ownsJavaEdition) {
                return try {
                    val profile = minecraftClient.getProfile(minecraftToken)
                    logger.info(
                        "Resolved Minecraft identity successfully (provider=${MinecraftIdentityProvider.PROVIDER_ID}, edition=java)"
                    )
                    ResolvedMinecraftIdentity(
                        brokerUserId = stableBrokerUserId,
                        username = profile.name,
                        loginIdentity = "java",
                        ownership = ownership,
                        minecraftJavaUuid = profile.formattedUuid,
                        minecraftJavaUsername = profile.name,
                        xboxGamertag = xboxGamertag,
                    )
                } catch (e: MinecraftProfileNotFoundException) {
                    if (ownership.ownsBedrockEdition) {
                        logger.warnf(
                            e,
                            "Resolved Minecraft Java profile missing; falling back to Bedrock identity (provider=%s)",
                            MinecraftIdentityProvider.PROVIDER_ID,
                        )
                        resolveBedrockIdentity(stableBrokerUserId, xboxGamertag, ownership)
                    } else {
                        logger.warnf(
                            e,
                            "Resolved Minecraft identity failed (provider=%s, edition=java, reason=%s)",
                            MinecraftIdentityProvider.PROVIDER_ID,
                            e.message ?: e.javaClass.simpleName,
                        )
                        throw IdentityBrokerException(
                            "Your account has Minecraft Java Edition but no Java profile exists yet. " +
                                "Please log into the Minecraft Launcher once to set up your profile."
                        )
                    }
                }
            }

            if (ownership.ownsBedrockEdition) {
                return resolveBedrockIdentity(stableBrokerUserId, xboxGamertag, ownership)
            }

            throw IdentityBrokerException(
                "This Microsoft account does not have a Minecraft Java or Bedrock entitlement."
            )
        } catch (e: XboxAuthException) {
            logger.warnf(
                e,
                "Authenticated with Xbox failed (provider=%s, xerr=%d, reason=%s, rawMessage=%s, redirect=%s)",
                MinecraftIdentityProvider.PROVIDER_ID,
                e.errorCode,
                e.message ?: e.javaClass.simpleName,
                e.rawMessage,
                e.redirectUrl,
            )
            throw IdentityBrokerException(e.message, e)
        } catch (e: XboxAuthenticationFailureException) {
            logger.errorf(
                e,
                "Authenticated with Xbox failed (provider=%s, stage=%s, reason=%s)",
                MinecraftIdentityProvider.PROVIDER_ID,
                e.stage,
                e.cause?.message ?: e.message ?: e.javaClass.simpleName,
            )
            throw IdentityBrokerException("Xbox Live authentication failed. Please try again.", e)
        } catch (e: IOException) {
            logger.errorf(
                e,
                "Authenticated with Minecraft services failed (provider=%s, reason=%s)",
                MinecraftIdentityProvider.PROVIDER_ID,
                e.message ?: e.javaClass.simpleName,
            )
            throw IdentityBrokerException("Minecraft authentication failed. Please try again.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warnf(
                e,
                "Authenticated with Minecraft services interrupted (provider=%s, reason=request_interrupted)",
                MinecraftIdentityProvider.PROVIDER_ID,
            )
            throw IdentityBrokerException("Minecraft authentication was interrupted.", e)
        }
    }

    /**
     * Builds the Bedrock-side identity when Java identity is unavailable or Bedrock is the only
     * owned edition.
     */
    private fun resolveBedrockIdentity(
        brokerUserId: String,
        xboxGamertag: String?,
        ownership: MinecraftApi.Ownership,
    ): ResolvedMinecraftIdentity {
        if (xboxGamertag.isNullOrBlank()) {
            throw IdentityBrokerException("Could not retrieve Xbox Gamertag for Bedrock user")
        }

        logger.info(
            "Resolved Minecraft identity successfully (provider=${MinecraftIdentityProvider.PROVIDER_ID}, edition=bedrock)"
        )

        return ResolvedMinecraftIdentity(
            brokerUserId = brokerUserId,
            username = xboxGamertag,
            loginIdentity = "bedrock",
            ownership = ownership,
            xboxGamertag = xboxGamertag,
        )
    }

    /**
     * Uses the XSTS user hash as the brokered user id because the Minecraft XSTS flow does not
     * guarantee a stable Xbox user identifier such as xid or ptx.
     */
    private fun resolveStableBrokerUserId(userHash: String): String = "xboxuhs-$userHash"

    companion object {
        private val logger = Logger.getLogger(MinecraftIdentityResolver::class.java)
    }
}
