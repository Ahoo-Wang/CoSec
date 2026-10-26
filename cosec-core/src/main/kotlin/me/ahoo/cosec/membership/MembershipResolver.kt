/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cosec.membership

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The memberships of a principal (RFC 0004 §4.5): from its token's claims, or from the [provider] when the token
 * carries [MembershipClaims.PROVIDER]. One principal has one source; claims and provider are never combined.
 *
 * Fails closed: the future completes with [MembershipUnavailableException], never with empty memberships, when
 *
 * - the token's marker is not [MembershipClaims.PROVIDER];
 * - the token carries the marker and also `roles` or `policies` claims, so its source is ambiguous;
 * - there is no [provider];
 * - the provider throws, fails, completes with `null`, or does not complete within [timeout].
 *
 * Nothing calls it on the authorization path yet: RFC 0004 PR-7 wires it in.
 */
class MembershipResolver(
    private val provider: MembershipProvider?,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        require(timeout > Duration.ZERO) { "The membership timeout must be positive, not $timeout." }
    }

    /**
     * Resolves the memberships of [principal], a member of [tenant]. Claims complete at once; a provider's answer
     * completes on the provider's thread, or on the JDK's delay scheduler when it times out.
     */
    @Suppress("TooGenericExceptionCaught")
    fun resolve(tenant: TenantId, principal: CoSecPrincipal): CompletableFuture<Memberships> {
        val marker = MembershipClaims.markerOf(principal)
            ?: return CompletableFuture.completedFuture(MembershipClaims.parse(principal.roles, principal.policies))
        if (marker != MembershipClaims.PROVIDER) {
            return unavailable("The token of principal [${principal.id}] has an unknown membership marker [$marker].")
        }
        if (principal.roles.isNotEmpty() || principal.policies.isNotEmpty()) {
            return unavailable(
                "The token of principal [${principal.id}] asks for a membership provider but also claims roles or policies."
            )
        }
        val provider = provider
            ?: return unavailable(
                "The token of principal [${principal.id}] asks for a membership provider, and none is configured."
            )
        // Nullable: a provider written in Java may return null.
        val answer: CompletableFuture<Memberships>? = try {
            provider.memberships(tenant, PrincipalId(principal.id))
        } catch (error: Exception) {
            return unavailable("The membership provider failed for principal [${principal.id}].", error)
        }
        if (answer == null) {
            return CompletableFuture.failedFuture(nothing(principal))
        }
        val resolved = CompletableFuture<Memberships>()
        // A copy, so that the timeout does not complete a future the provider may share with other callers.
        answer.copy().orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS).whenComplete { memberships, error ->
            when {
                error != null -> resolved.completeExceptionally(failure(principal, unwrap(error)))
                memberships == null -> resolved.completeExceptionally(nothing(principal))

                else -> resolved.complete(memberships)
            }
        }
        return resolved
    }

    private fun failure(principal: CoSecPrincipal, error: Throwable): MembershipUnavailableException {
        val message = if (error is TimeoutException) {
            "The membership provider did not answer within $timeout for principal [${principal.id}]."
        } else {
            "The membership provider failed for principal [${principal.id}]."
        }
        return MembershipUnavailableException(message, error)
    }

    private fun nothing(principal: CoSecPrincipal) =
        MembershipUnavailableException("The membership provider answered nothing for principal [${principal.id}].")

    private fun unwrap(error: Throwable): Throwable =
        if ((error is CompletionException || error is ExecutionException) && error.cause != null) error.cause!! else error

    private fun unavailable(message: String, cause: Throwable? = null): CompletableFuture<Memberships> =
        CompletableFuture.failedFuture(MembershipUnavailableException(message, cause))

    companion object {
        /** RFC 0004 §4.5.2: `cosec.authorization.membership.timeout`, once RFC 0004 PR-7 adds the property. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}
