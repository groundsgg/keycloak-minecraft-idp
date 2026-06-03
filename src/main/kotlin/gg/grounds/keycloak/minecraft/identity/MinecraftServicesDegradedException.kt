package gg.grounds.keycloak.minecraft.identity

import org.keycloak.broker.provider.IdentityBrokerException

/**
 * Signals that the Minecraft Services API was unavailable (5xx/429) *after* the stable broker user
 * id had already been resolved from the validated partner XSTS token (which does not depend on
 * Minecraft Services).
 *
 * It carries [stableBrokerUserId] so the identity provider can fall back to the existing federated
 * user for a returning login — letting the team and existing users in during a Minecraft Services
 * outage — instead of failing the whole login. A first-time login still cannot be completed offline
 * (ownership/UUID are only resolvable via Minecraft Services), so the provider fails those cleanly.
 *
 * Extends [IdentityBrokerException] (a `RuntimeException`) so it is not swallowed by the resolver's
 * `catch (IOException)` and propagates to the provider, which can then decide whether to degrade.
 */
class MinecraftServicesDegradedException(val stableBrokerUserId: String, cause: Throwable) :
    IdentityBrokerException("Minecraft services unavailable; degraded-login path", cause)
