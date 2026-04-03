package gg.grounds.keycloak.minecraft.api.exceptions

import java.io.IOException

/**
 * Represents a known Xbox Live or XSTS protocol error returned with an `XErr` code.
 *
 * The exception message is converted into a user-facing explanation and may include a redirect URL
 * when Xbox supplies one for remediation.
 */
class XboxAuthException(val errorCode: Long, val rawMessage: String?, val redirectUrl: String?) :
    IOException(buildMessage(errorCode, redirectUrl)) {

    companion object {
        fun buildMessage(errorCode: Long, redirectUrl: String?): String {
            val base =
                when (errorCode) {
                    2148916227L -> "This account has been banned from Xbox Live."
                    2148916233L ->
                        "This Microsoft account has no Xbox account. Please create one at xbox.com/live"
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
