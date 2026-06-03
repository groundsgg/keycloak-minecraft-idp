package gg.grounds.keycloak.minecraft.api.exceptions

import java.io.IOException

/**
 * Thrown when a Minecraft Services API call fails with a transient, server-side status (5xx or 429
 * rate-limit) — i.e. the upstream is unavailable rather than the request being invalid.
 *
 * It is an [IOException] subtype so existing callers that only catch [IOException] keep working,
 * while callers that want to distinguish an outage (so they can degrade gracefully) can catch this
 * specific type and read [statusCode].
 */
class MinecraftServiceUnavailableException(message: String, val statusCode: Int) :
    IOException(message)
