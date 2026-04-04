package gg.grounds.keycloak.minecraft.api

import java.net.http.HttpClient
import java.time.Duration

/**
 * Shared HTTP client for all Minecraft/Xbox/Microsoft API calls.
 *
 * The provider factory closes this client during Keycloak shutdown so the plugin does not leave the
 * underlying HTTP resources alive across server lifecycle transitions.
 */
internal object SharedApiClient : AutoCloseable {
    val httpClient: HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    override fun close() {
        httpClient.close()
    }
}
