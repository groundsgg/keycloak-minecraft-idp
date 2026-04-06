package gg.grounds.keycloak.minecraft.testsupport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.CompressionAlgorithm
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import gg.grounds.keycloak.minecraft.MinecraftIdentityProvider
import gg.grounds.keycloak.minecraft.api.MinecraftApi
import gg.grounds.keycloak.minecraft.api.MinecraftClient
import gg.grounds.keycloak.minecraft.api.XboxAuthApi
import gg.grounds.keycloak.minecraft.api.XboxAuthClient
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Optional
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.keycloak.broker.provider.AbstractIdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
import org.keycloak.events.EventBuilder
import org.keycloak.http.simple.SimpleHttpRequest
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.sessions.AuthenticationSessionModel
import org.keycloak.vault.VaultCharSecret
import org.keycloak.vault.VaultRawSecret
import org.keycloak.vault.VaultStringSecret
import org.keycloak.vault.VaultTranscriber

internal fun newSimpleHttpRequest(
    url: String = "https://login.live.com/oauth20_token.srf"
): SimpleHttpRequest {
    val simpleHttpMethodClass = Class.forName("org.keycloak.http.simple.SimpleHttpMethod")
    val postMethod =
        simpleHttpMethodClass.enumConstants.first { enumConstant ->
            (enumConstant as Enum<*>).name == "POST"
        }
    val constructor =
        SimpleHttpRequest::class.java.declaredConstructors.single { candidate ->
            candidate.parameterTypes.size == 6
        }
    constructor.isAccessible = true
    return constructor.newInstance(url, postMethod, null, null, 0L, ObjectMapper())
        as SimpleHttpRequest
}

internal fun testKeycloakSession(
    vaultTranscriber: VaultTranscriber = UnsupportedVaultTranscriber()
): KeycloakSession =
    Proxy.newProxyInstance(
        KeycloakSession::class.java.classLoader,
        arrayOf(KeycloakSession::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "vault" -> vaultTranscriber
            "toString" -> "TestKeycloakSession"
            "hashCode" -> 0
            "equals" -> false
            else ->
                throw UnsupportedOperationException(
                    "Unexpected KeycloakSession method invoked during test (method=${method.name})"
                )
        }
    } as KeycloakSession

internal fun authenticationSession(isNewUser: Boolean): AuthenticationSessionModel =
    Proxy.newProxyInstance(
        AuthenticationSessionModel::class.java.classLoader,
        arrayOf(AuthenticationSessionModel::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAuthNote" ->
                if (args?.get(0) == AbstractIdentityProvider.BROKER_REGISTERED_NEW_USER) {
                    isNewUser.toString()
                } else {
                    null
                }
            "toString" -> "TestAuthenticationSession"
            "hashCode" -> 0
            "equals" -> false
            else ->
                throw UnsupportedOperationException(
                    "Unexpected AuthenticationSessionModel method invoked during test (method=${method.name})"
                )
        }
    } as AuthenticationSessionModel

internal fun realmModel(): RealmModel =
    Proxy.newProxyInstance(RealmModel::class.java.classLoader, arrayOf(RealmModel::class.java)) {
        _,
        method,
        _ ->
        when (method.name) {
            "toString" -> "TestRealmModel"
            "hashCode" -> 0
            "equals" -> false
            else ->
                throw UnsupportedOperationException(
                    "Unexpected RealmModel method invoked during test (method=${method.name})"
                )
        }
    } as RealmModel

internal fun recordingUserModel(state: RecordingUserState): UserModel =
    Proxy.newProxyInstance(UserModel::class.java.classLoader, arrayOf(UserModel::class.java)) {
        _,
        method,
        args ->
        when (method.name) {
            "getUsername" -> state.username
            "getFirstName" -> state.firstName
            "setFirstName" -> {
                state.firstName = args?.get(0) as String?
                null
            }
            "getLastName" -> state.lastName
            "setLastName" -> {
                state.lastName = args?.get(0) as String?
                null
            }
            "getEmail" -> state.email
            "setEmail" -> {
                state.email = args?.get(0) as String?
                null
            }
            "setEmailVerified" -> {
                state.emailVerified = args?.get(0) as Boolean
                null
            }
            "setAttribute" -> {
                @Suppress("UNCHECKED_CAST")
                state.attributes[args?.get(0) as String] = (args[1] as List<String>).toMutableList()
                null
            }
            "removeAttribute" -> {
                state.attributes.remove(args?.get(0) as String)
                null
            }
            "getAttributes" -> state.attributes
            "toString" -> "RecordingUserModel"
            "hashCode" -> 0
            "equals" -> false
            else ->
                throw UnsupportedOperationException(
                    "Unexpected UserModel method invoked during test (method=${method.name})"
                )
        }
    } as UserModel

internal fun invokeDoGetFederatedIdentity(
    provider: MinecraftIdentityProvider,
    accessToken: String = "ms-access-token",
): BrokeredIdentityContext =
    try {
        MinecraftIdentityProvider::class
            .java
            .getDeclaredMethod("doGetFederatedIdentity", String::class.java)
            .apply { isAccessible = true }
            .invoke(provider, accessToken) as BrokeredIdentityContext
    } catch (exception: InvocationTargetException) {
        throw (exception.cause ?: exception)
    }

internal fun invokeExtractIdentityFromProfile(
    provider: MinecraftIdentityProvider,
    profile: JsonNode,
): BrokeredIdentityContext =
    try {
        MinecraftIdentityProvider::class
            .java
            .getDeclaredMethod(
                "extractIdentityFromProfile",
                EventBuilder::class.java,
                JsonNode::class.java,
            )
            .apply { isAccessible = true }
            .invoke(provider, null, profile) as BrokeredIdentityContext
    } catch (exception: InvocationTargetException) {
        throw (exception.cause ?: exception)
    }

internal fun xboxResponse(
    token: String,
    userHash: String?,
    gamertag: String? = null,
    partnerXboxUserId: String? = null,
): XboxAuthApi.XboxAuthResponse =
    XboxAuthApi.XboxAuthResponse(
        token = token,
        displayClaims =
            XboxAuthApi.DisplayClaims(
                xui =
                    listOf(
                        XboxAuthApi.XuiClaim(
                            uhs = userHash,
                            gtg = gamertag,
                            ptx = partnerXboxUserId,
                        )
                    )
            ),
    )

internal fun generateRsaKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

internal fun toPkcs8Pem(keyPair: KeyPair): String {
    val encodedKey =
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
    return buildString {
        appendLine("-----BEGIN PRIVATE KEY-----")
        appendLine(encodedKey)
        append("-----END PRIVATE KEY-----")
    }
}

internal fun toPkcs1Pem(keyPair: KeyPair): String =
    StringWriter().use { stringWriter ->
        JcaPEMWriter(stringWriter).use { pemWriter ->
            pemWriter.writeObject(JcaMiscPEMGenerator(keyPair.private))
        }
        stringWriter.toString()
    }

internal fun encryptedPartnerXstsToken(keyPair: KeyPair, claimsJson: String): String {
    val header =
        JWEHeader.Builder(JWEAlgorithm.parse("RSA-OAEP"), EncryptionMethod.A128CBC_HS256)
            .contentType("JWT")
            .compressionAlgorithm(CompressionAlgorithm.DEF)
            .build()
    val jweObject = JWEObject(header, Payload(unsignedJwt(claimsJson)))
    jweObject.encrypt(RSAEncrypter(keyPair.public as RSAPublicKey))
    return jweObject.serialize()
}

private fun unsignedJwt(claimsJson: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload = encoder.encodeToString(claimsJson.toByteArray())
    val signature = encoder.encodeToString("signature".toByteArray())
    return "$header.$payload.$signature"
}

internal class FakeXboxAuthClient(
    private val authenticateHandler: (String) -> XboxAuthApi.XboxAuthResponse,
    private val minecraftXstsHandler: (String) -> XboxAuthApi.XboxAuthResponse,
    private val partnerXstsHandler: (String, String) -> XboxAuthApi.XboxAuthResponse = { _, _ ->
        xboxResponse(
            token = "partner-xsts-token",
            userHash = null,
            partnerXboxUserId = "partner-123",
        )
    },
) : XboxAuthClient {
    val microsoftAccessTokens = mutableListOf<String>()
    val minecraftXstsUserTokens = mutableListOf<String>()
    val partnerXstsRequests = mutableListOf<Pair<String, String>>()

    override fun authenticateWithXbox(microsoftAccessToken: String): XboxAuthApi.XboxAuthResponse {
        microsoftAccessTokens += microsoftAccessToken
        return authenticateHandler(microsoftAccessToken)
    }

    override fun obtainMinecraftXstsToken(xboxUserToken: String): XboxAuthApi.XboxAuthResponse {
        minecraftXstsUserTokens += xboxUserToken
        return minecraftXstsHandler(xboxUserToken)
    }

    override fun obtainPartnerXstsToken(
        xboxUserToken: String,
        relyingParty: String,
    ): XboxAuthApi.XboxAuthResponse {
        partnerXstsRequests += xboxUserToken to relyingParty
        return partnerXstsHandler(xboxUserToken, relyingParty)
    }
}

internal class FakeMinecraftClient(
    private val authenticateHandler: (String, String) -> MinecraftApi.MinecraftAuthResponse,
    private val ownershipHandler: (String) -> MinecraftApi.Ownership,
    private val profileHandler: (String) -> MinecraftApi.MinecraftProfile,
) : MinecraftClient {
    val minecraftAuthRequests = mutableListOf<Pair<String, String>>()
    val ownershipRequests = mutableListOf<String>()
    val profileRequests = mutableListOf<String>()

    override fun authenticateWithMinecraft(
        userHash: String,
        xstsToken: String,
    ): MinecraftApi.MinecraftAuthResponse {
        minecraftAuthRequests += userHash to xstsToken
        return authenticateHandler(userHash, xstsToken)
    }

    override fun getOwnership(minecraftAccessToken: String): MinecraftApi.Ownership {
        ownershipRequests += minecraftAccessToken
        return ownershipHandler(minecraftAccessToken)
    }

    override fun getProfile(minecraftAccessToken: String): MinecraftApi.MinecraftProfile {
        profileRequests += minecraftAccessToken
        return profileHandler(minecraftAccessToken)
    }
}

internal class RecordingVaultTranscriber(secretValue: String?) : VaultTranscriber {
    val requestedSecretKeys = mutableListOf<String>()
    val secret = RecordingVaultStringSecret(secretValue)

    override fun getRawSecret(key: String): VaultRawSecret =
        throw UnsupportedOperationException("Raw secret access is not expected in these tests")

    override fun getCharSecret(key: String): VaultCharSecret =
        throw UnsupportedOperationException("Char secret access is not expected in these tests")

    override fun getStringSecret(key: String): VaultStringSecret {
        requestedSecretKeys += key
        return secret
    }
}

internal class RecordingVaultStringSecret(secretValue: String?) : VaultStringSecret {
    private val optionalSecret = Optional.ofNullable(secretValue)
    var closed = false

    override fun get(): Optional<String> = optionalSecret

    override fun close() {
        closed = true
    }
}

internal data class RecordingUserState(
    val username: String,
    var firstName: String? = null,
    var lastName: String? = null,
    var email: String? = null,
    var emailVerified: Boolean = false,
    val attributes: MutableMap<String, MutableList<String>> = mutableMapOf(),
)

private class UnsupportedVaultTranscriber : VaultTranscriber {
    override fun getRawSecret(key: String): VaultRawSecret =
        throw UnsupportedOperationException("Vault access is not expected in these tests")

    override fun getCharSecret(key: String): VaultCharSecret =
        throw UnsupportedOperationException("Vault access is not expected in these tests")

    override fun getStringSecret(key: String): VaultStringSecret =
        throw UnsupportedOperationException("Vault access is not expected in these tests")
}
