package gg.grounds.keycloak.minecraft.identity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.interfaces.RSAPrivateKey
import java.util.Base64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.keycloak.jose.jws.JWSInput

class PartnerXstsTokenInspector(private val privateKey: RSAPrivateKey?) {

    internal fun inspect(token: String): PartnerXstsTokenInspection {
        val header = parseOuterHeader(token)
        if (privateKey == null) {
            return PartnerXstsTokenInspection(outerHeader = header, decryptionConfigured = false)
        }
        if (!looksEncrypted(token, header)) {
            return PartnerXstsTokenInspection(outerHeader = header, decryptionConfigured = true)
        }

        val jweObject = JWEObject.parse(token)
        jweObject.decrypt(RSADecrypter(privateKey))
        val payload = jweObject.payload.toString()
        val claims = parseClaims(payload)
        return PartnerXstsTokenInspection(
            partnerXboxUserId = claims.findFirstText("ptx"),
            gamertag = claims.findFirstText("gtg"),
            outerHeader = header,
            decryptionConfigured = true,
            decrypted = true,
            topLevelClaimKeys = claims.objectFieldNames(),
            xuiClaimKeys = claims.findNestedObjectFieldNames("xui"),
        )
    }

    companion object {
        private val objectMapper = ObjectMapper()
        private val pemKeyConverter = JcaPEMKeyConverter()

        internal fun fromPemReference(reference: String?): PartnerXstsTokenInspector {
            if (reference.isNullOrBlank()) {
                return PartnerXstsTokenInspector(privateKey = null)
            }

            val pem = loadPem(reference)
            return PartnerXstsTokenInspector(parsePrivateKey(pem))
        }

        internal fun readOuterHeader(token: String): OuterHeader =
            try {
                parseOuterHeader(token)
            } catch (_: Exception) {
                OuterHeader()
            }

        private fun loadPem(reference: String): String {
            val trimmedReference = reference.trim()
            val path =
                when {
                    trimmedReference.startsWith("file:") ->
                        Paths.get(java.net.URI.create(trimmedReference))
                    trimmedReference.startsWith("/") ||
                        trimmedReference.startsWith("./") ||
                        trimmedReference.startsWith("../") -> Path.of(trimmedReference)
                    else -> return trimmedReference
                }
            return Files.readString(path)
        }

        private fun parsePrivateKey(pem: String): RSAPrivateKey {
            val normalizedPem = normalizePem(pem)
            val key =
                PEMParser(StringReader(normalizedPem)).use { parser ->
                    when (val pemObject = parser.readObject()) {
                        is PEMKeyPair -> pemKeyConverter.getPrivateKey(pemObject.privateKeyInfo)
                        is PrivateKeyInfo -> pemKeyConverter.getPrivateKey(pemObject)
                        else -> throw IllegalArgumentException(UNSUPPORTED_PEM_FORMAT_MESSAGE)
                    }
                }

            return key as? RSAPrivateKey
                ?: throw IllegalArgumentException(
                    "Unsupported private key type for partner XSTS token decryption"
                )
        }

        private fun normalizePem(pem: String): String {
            val normalizedLineBreaks =
                pem.trim().replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n")

            val markers =
                when {
                    normalizedLineBreaks.contains(PKCS8_BEGIN_MARKER) ->
                        PKCS8_BEGIN_MARKER to PKCS8_END_MARKER
                    normalizedLineBreaks.contains(PKCS1_BEGIN_MARKER) ->
                        PKCS1_BEGIN_MARKER to PKCS1_END_MARKER
                    else -> throw IllegalArgumentException(UNSUPPORTED_PEM_FORMAT_MESSAGE)
                }

            val body =
                normalizedLineBreaks
                    .substringAfter(markers.first)
                    .substringBefore(markers.second)
                    .replace(Regex("\\s"), "")

            require(body.isNotBlank()) { UNSUPPORTED_PEM_FORMAT_MESSAGE }

            return buildString {
                appendLine(markers.first)
                body.chunked(64).forEach { appendLine(it) }
                append(markers.second)
            }
        }

        private fun parseOuterHeader(token: String): OuterHeader {
            val headerSegment =
                token.substringBefore('.', "").takeIf { it.isNotBlank() } ?: return OuterHeader()
            val headerJson = Base64.getUrlDecoder().decode(headerSegment).toString(Charsets.UTF_8)
            return objectMapper.readValue(headerJson, OuterHeader::class.java)
        }

        private fun parseClaims(payload: String): JsonNode =
            if (payload.count { it == '.' } >= 2) {
                objectMapper.readTree(JWSInput(payload).content)
            } else {
                objectMapper.readTree(payload)
            }

        private fun looksEncrypted(token: String, header: OuterHeader): Boolean =
            token.count { it == '.' } == 4 && !header.encryption.isNullOrBlank()

        private const val PKCS8_BEGIN_MARKER = "-----BEGIN PRIVATE KEY-----"
        private const val PKCS8_END_MARKER = "-----END PRIVATE KEY-----"
        private const val PKCS1_BEGIN_MARKER = "-----BEGIN RSA PRIVATE KEY-----"
        private const val PKCS1_END_MARKER = "-----END RSA PRIVATE KEY-----"
        private const val UNSUPPORTED_PEM_FORMAT_MESSAGE =
            "Unsupported PEM format for partner XSTS private key"
    }
}

internal data class PartnerXstsTokenInspection(
    val partnerXboxUserId: String? = null,
    val gamertag: String? = null,
    val outerHeader: OuterHeader = OuterHeader(),
    val decryptionConfigured: Boolean,
    val decrypted: Boolean = false,
    val topLevelClaimKeys: Set<String> = emptySet(),
    val xuiClaimKeys: Set<String> = emptySet(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OuterHeader(
    @param:JsonProperty("alg") val algorithm: String? = null,
    @param:JsonProperty("enc") val encryption: String? = null,
    @param:JsonProperty("zip") val compression: String? = null,
    @param:JsonProperty("x5t") val thumbprint: String? = null,
)

private fun JsonNode.findFirstText(fieldName: String): String? {
    if (isObject) {
        get(fieldName)
            ?.asText()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        val iterator = properties().iterator()
        while (iterator.hasNext()) {
            val (_, value) = iterator.next()
            value.findFirstText(fieldName)?.let {
                return it
            }
        }
    } else if (isArray) {
        val iterator = elements()
        while (iterator.hasNext()) {
            val element = iterator.next()
            element.findFirstText(fieldName)?.let {
                return it
            }
        }
    }
    return null
}

private fun JsonNode.objectFieldNames(): Set<String> =
    if (isObject) {
        properties().asSequence().map { it.key }.toSortedSet()
    } else {
        emptySet()
    }

private fun JsonNode.findNestedObjectFieldNames(fieldName: String): Set<String> {
    if (isArray) {
        val iterator = elements()
        while (iterator.hasNext()) {
            val nestedResult = iterator.next().findNestedObjectFieldNames(fieldName)
            if (nestedResult.isNotEmpty()) {
                return nestedResult
            }
        }
        return emptySet()
    }
    if (!isObject) {
        return emptySet()
    }

    val directChild = get(fieldName)
    if (directChild != null) {
        return when {
            directChild.isObject -> directChild.objectFieldNames()
            directChild.isArray ->
                directChild.elements().asSequence().firstOrNull()?.objectFieldNames() ?: emptySet()
            else -> emptySet()
        }
    }

    val iterator = properties().iterator()
    while (iterator.hasNext()) {
        val (_, value) = iterator.next()
        val nestedResult = value.findNestedObjectFieldNames(fieldName)
        if (nestedResult.isNotEmpty()) {
            return nestedResult
        }
    }

    return emptySet()
}
