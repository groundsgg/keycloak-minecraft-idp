package gg.grounds.keycloak.minecraft.api

import java.net.http.HttpClient
import java.time.Duration

/**
 * Shared HTTP client for all Minecraft/Xbox/Microsoft API calls.
 * Single instance with connection timeout to prevent hanging threads.
 */
internal val sharedHttpClient: HttpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .connectTimeout(Duration.ofSeconds(10))
    .build()
