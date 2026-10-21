package gg.grounds.keycloak.minecraft.identity

import java.util.UUID
import org.keycloak.broker.provider.IdentityBrokerException

/** Validates authoritative Xbox XID claims and converts them to Floodgate's UUID(0L, xuid). */
internal object XboxXuidIdentity {
    private val canonicalPositiveDecimal = Regex("[1-9][0-9]*")

    fun resolveBedrockUuid(rawClaims: Iterable<String>): String? {
        val xuids = rawClaims.map { raw ->
            if (!canonicalPositiveDecimal.matches(raw)) {
                throw invalidXid()
            }
            raw.toLongOrNull()?.takeIf { it > 0 } ?: throw invalidXid()
        }
        if (xuids.isEmpty()) {
            return null
        }
        val distinct = xuids.toSet()
        if (distinct.size != 1) {
            throw IdentityBrokerException("Xbox XID claims are conflicting")
        }
        return UUID(0L, distinct.single()).toString()
    }

    private fun invalidXid() = IdentityBrokerException("Xbox XID claim is invalid")
}
