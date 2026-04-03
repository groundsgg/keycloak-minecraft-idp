package gg.grounds.keycloak.minecraft.api

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
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

        val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.ofString())

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

        val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.ofString())

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

        val xboxUserId: String?
            get() = displayClaims?.xui?.firstOrNull()?.xid
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
        @param:JsonProperty("xid") val xid: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XstsErrorResponse
    @JsonCreator
    constructor(
        @param:JsonProperty("XErr") val xErr: Long = 0,
        @param:JsonProperty("Message") val message: String?,
        @param:JsonProperty("Redirect") val redirect: String?,
    )

    /**
     * Exception for known Xbox Live authentication error codes. Includes redirect URL in the
     * message where provided (e.g., child-account setup links).
     */
    class XboxAuthException(
        val errorCode: Long,
        val rawMessage: String?,
        val redirectUrl: String?,
    ) : IOException(buildMessage(errorCode, redirectUrl)) {

        companion object {
            fun buildMessage(errorCode: Long, redirectUrl: String?): String {
                val base =
                    when (errorCode) {
                        2148916227L -> "This account has been banned from Xbox Live."
                        2148916233L ->
                            "This Microsoft account has no Xbox account. " +
                                "Please create one at xbox.com/live"
                        2148916235L -> "Xbox Live is not available in your country."
                        2148916236L,
                        2148916237L -> "This account requires adult verification (South Korea)."
                        2148916238L -> "This is a child account and needs to be added to a family."
                        else -> "Xbox Live authentication failed (Error: $errorCode)"
                    }
                return if (!redirectUrl.isNullOrBlank()) "$base ($redirectUrl)" else base
            }
        }
    }

    companion object {
        private const val XBOX_USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XBOX_XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        // Shared, thread-safe after configuration
        internal val objectMapper: ObjectMapper = ObjectMapper()
    }
}
