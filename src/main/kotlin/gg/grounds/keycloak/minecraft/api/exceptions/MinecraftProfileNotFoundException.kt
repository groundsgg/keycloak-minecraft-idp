package gg.grounds.keycloak.minecraft.api.exceptions

import java.io.IOException

/**
 * Indicates that the account owns Java Edition but does not yet have a Java profile provisioned.
 * This commonly happens before the user has opened the official Minecraft launcher.
 */
class MinecraftProfileNotFoundException(message: String) : IOException(message)
