package gg.grounds.keycloak.minecraft.api

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.keycloak.minecraft.api.exceptions.XboxAuthException
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Handles Xbox Live authentication to obtain Xbox User Token and XSTS Token. */
interface XboxAuthClient {
    fun authenticateWithXbox(microsoftAccessToken: String): XboxAuthApi.XboxAuthResponse

    fun obtainXstsToken(xboxUserToken: String): XboxAuthApi.XboxAuthResponse
}

/** Handles Xbox Live authentication to obtain Xbox User Token and XSTS Token. */
class XboxAuthApi : XboxAuthClient {

    /**
     * Authenticates with Xbox Live using a Microsoft access token. Returns an XboxAuthResponse
     * containing the Xbox User Token and user hash.
     */
    override fun authenticateWithXbox(microsoftAccessToken: String): XboxAuthResponse {
        val requestBody =
            mapOf(
                "Properties" to
                    mapOf(
                        "AuthMethod" to "RPS",
                        "SiteName" to "user.auth.xboxlive.com",
                        "RpsTicket" to "d=$microsoftAccessToken",
                    ),
                // Microsoft Learn's Xbox user-auth example uses this exact relying-party value:
                // https://learn.microsoft.com/en-us/gaming/gdk/docs/services/fundamentals/s2s-auth-calls/service-authentication/live-website-authentication
                // The `http://` scheme is part of the protocol identifier, not a transport URL to
                // upgrade.
                "RelyingParty" to "http://auth.xboxlive.com",
                "TokenType" to "JWT",
            )

        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(XBOX_USER_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody)
                    )
                )
                .build()

        val response =
            SharedApiClient.httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IOException("Xbox authentication failed with status: ${response.statusCode()}")
        }

        return objectMapper.readValue(response.body(), XboxAuthResponse::class.java)
    }

    /**
     * Obtains an XSTS token scoped to Minecraft services. Throws XboxAuthException for known Xbox
     * Live error codes.
     */
    override fun obtainXstsToken(xboxUserToken: String): XboxAuthResponse {
        val requestBody =
            mapOf(
                "Properties" to
                    mapOf("SandboxId" to "RETAIL", "UserTokens" to listOf(xboxUserToken)),
                "RelyingParty" to "rp://api.minecraftservices.com/",
                "TokenType" to "JWT",
            )

        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(XBOX_XSTS_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody)
                    )
                )
                .build()

        val response =
            SharedApiClient.httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            if (response.statusCode() == 401) {
                try {
                    val error =
                        objectMapper.readValue(response.body(), XstsErrorResponse::class.java)
                    throw XboxAuthException(error.xErr, error.message, error.redirect)
                } catch (e: JsonProcessingException) {
                    throw IOException(
                        "XSTS authentication failed with status 401, body: ${response.body()}",
                        e,
                    )
                }
            }

            throw IOException("XSTS token request failed with status: ${response.statusCode()}")
        }

        return objectMapper.readValue(response.body(), XboxAuthResponse::class.java)
    }

    // --- Response types ---
    // @JsonCreator on the primary constructor lets Jackson use it unambiguously without
    // requiring jackson-module-kotlin (and the ~4 MB kotlin-reflect it pulls in).

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XboxAuthResponse
    @JsonCreator
    constructor(
        @param:JsonProperty("Token") val token: String,
        @param:JsonProperty("DisplayClaims") val displayClaims: DisplayClaims?,
    ) {
        val userHash: String?
            get() = displayClaims?.xui?.firstOrNull()?.uhs

        val gamertag: String?
            get() = displayClaims?.xui?.firstOrNull()?.gtg
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DisplayClaims
    @JsonCreator
    constructor(@param:JsonProperty("xui") val xui: List<XuiClaim>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XuiClaim
    @JsonCreator
    constructor(
        @param:JsonProperty("uhs") val uhs: String?,
        @param:JsonProperty("gtg") val gtg: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XstsErrorResponse
    @JsonCreator
    constructor(
        @param:JsonProperty("XErr") val xErr: Long = 0,
        @param:JsonProperty("Message") val message: String?,
        @param:JsonProperty("Redirect") val redirect: String?,
    )

    companion object {
        private const val XBOX_USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XBOX_XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        // Shared, thread-safe after configuration
        internal val objectMapper: ObjectMapper = ObjectMapper()
    }
}
