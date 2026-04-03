package gg.grounds.keycloak.minecraft.api

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.jboss.logging.Logger

/**
 * Handles Minecraft Services API calls: authentication, entitlement checks, and profile retrieval.
 */
interface MinecraftClient {
    fun authenticateWithMinecraft(
        userHash: String,
        xstsToken: String,
    ): MinecraftApi.MinecraftAuthResponse

    fun getOwnership(minecraftAccessToken: String): MinecraftApi.Ownership

    fun getProfile(minecraftAccessToken: String): MinecraftApi.MinecraftProfile
}

/**
 * Handles Minecraft Services API calls: authentication, entitlement checks, and profile retrieval.
 */
class MinecraftApi : MinecraftClient {

    /** Authenticates with Minecraft services using the Xbox XSTS token. */
    override fun authenticateWithMinecraft(
        userHash: String,
        xstsToken: String,
    ): MinecraftAuthResponse {
        val requestBody = mapOf("identityToken" to "XBL3.0 x=$userHash;$xstsToken")

        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(MINECRAFT_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody)
                    )
                )
                .build()

        val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IOException(
                "Minecraft authentication failed with status: ${response.statusCode()}"
            )
        }

        return objectMapper.readValue(response.body(), MinecraftAuthResponse::class.java)
    }

    /** Resolves Minecraft entitlements for the authenticated account. */
    override fun getOwnership(minecraftAccessToken: String): Ownership {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(MINECRAFT_LICENSES_URL))
                .header("Authorization", "Bearer $minecraftAccessToken")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()

        val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IOException("Entitlements check failed with status: ${response.statusCode()}")
        }

        val entitlements = objectMapper.readValue(response.body(), EntitlementsResponse::class.java)
        val entitlementNames = entitlements.items?.map { it.name }?.toSet().orEmpty()
        val ownership = resolveOwnership(entitlementNames)
        logger.debugf(
            "Checked Minecraft ownership (entitlementNames=%s, ownsJava=%b, ownsBedrock=%b)",
            entitlementNames,
            ownership.ownsJavaEdition,
            ownership.ownsBedrockEdition,
        )
        return ownership
    }

    /**
     * Gets the Minecraft Java Edition profile (username + UUID).
     *
     * Throws [MinecraftProfileNotFoundException] when the profile does not exist yet, e.g., a Game
     * Pass user who hasn't launched the game through the official launcher.
     */
    override fun getProfile(minecraftAccessToken: String): MinecraftProfile {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(MINECRAFT_PROFILE_URL))
                .header("Authorization", "Bearer $minecraftAccessToken")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()

        val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 404) {
            throw MinecraftProfileNotFoundException(
                "The user has no Minecraft Java Edition profile"
            )
        }

        if (response.statusCode() != 200) {
            throw IOException(
                "Minecraft profile request failed with status: ${response.statusCode()}"
            )
        }

        return objectMapper.readValue(response.body(), MinecraftProfile::class.java)
    }

    // --- Response types ---
    // @JsonCreator on the primary constructor lets Jackson use it unambiguously without
    // requiring jackson-module-kotlin (and the ~4 MB kotlin-reflect it pulls in).

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MinecraftAuthResponse
    @JsonCreator
    constructor(
        @param:JsonProperty("access_token") val accessToken: String,
        @param:JsonProperty("token_type") val tokenType: String?,
        @param:JsonProperty("expires_in") val expiresIn: Int,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EntitlementsResponse
    @JsonCreator
    constructor(@param:JsonProperty("items") val items: List<EntitlementItem>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EntitlementItem
    @JsonCreator
    constructor(@param:JsonProperty("name") val name: String)

    data class Ownership(
        val entitlementNames: Set<String>,
        val ownsJavaEdition: Boolean,
        val ownsBedrockEdition: Boolean,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MinecraftProfile
    @JsonCreator
    constructor(
        @param:JsonProperty("id") val id: String,
        @param:JsonProperty("name") val name: String,
        @param:JsonProperty("skins") val skins: List<Skin>?,
        @param:JsonProperty("capes") val capes: List<Cape>?,
    ) {
        /** UUID in standard hyphenated format (e.g., 550e8400-e29b-41d4-a716-446655440000) */
        val formattedUuid: String
            get() =
                if (id.length == 32) {
                    "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}" +
                        "-${id.substring(16, 20)}-${id.substring(20)}"
                } else {
                    id
                }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Skin
    @JsonCreator
    constructor(
        @param:JsonProperty("id") val id: String?,
        @param:JsonProperty("state") val state: String?,
        @param:JsonProperty("url") val url: String?,
        @param:JsonProperty("variant") val variant: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Cape
    @JsonCreator
    constructor(
        @param:JsonProperty("id") val id: String?,
        @param:JsonProperty("state") val state: String?,
        @param:JsonProperty("url") val url: String?,
        @param:JsonProperty("alias") val alias: String?,
    )

    /** Thrown when an account has Java Edition entitlement but no profile yet (e.g., Game Pass). */
    class MinecraftProfileNotFoundException(message: String) : IOException(message)

    companion object {
        private val logger = Logger.getLogger(MinecraftApi::class.java)
        // Shared, thread-safe after configuration
        internal val objectMapper: ObjectMapper = ObjectMapper()
        private const val MINECRAFT_AUTH_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox"
        private const val MINECRAFT_LICENSES_URL =
            "https://api.minecraftservices.com/entitlements/license"
        private const val MINECRAFT_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile"

        internal fun resolveOwnership(entitlementNames: Set<String>): Ownership =
            Ownership(
                entitlementNames = entitlementNames,
                ownsJavaEdition = entitlementNames.any { it in JAVA_EDITION_ENTITLEMENTS },
                ownsBedrockEdition = entitlementNames.any { it in BEDROCK_EDITION_ENTITLEMENTS },
            )

        /** Entitlement item names that indicate Java Edition ownership. */
        private val JAVA_EDITION_ENTITLEMENTS = setOf("product_minecraft", "game_minecraft")

        /** Entitlement item names that indicate Bedrock Edition ownership. */
        private val BEDROCK_EDITION_ENTITLEMENTS =
            setOf("product_minecraft_bedrock", "game_minecraft_bedrock")
    }
}
