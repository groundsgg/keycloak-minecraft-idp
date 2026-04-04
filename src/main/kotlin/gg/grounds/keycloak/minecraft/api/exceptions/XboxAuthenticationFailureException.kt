package gg.grounds.keycloak.minecraft.api.exceptions

import java.io.IOException

/**
 * Wraps unexpected transport or parsing failures that happen during a specific Xbox authentication
 * stage so the provider can distinguish them from Minecraft-service failures.
 */
class XboxAuthenticationFailureException(val stage: String, cause: IOException) :
    IOException(cause.message, cause)
