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

package me.ahoo.cosec.runtime.authn

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.membership.MembershipClaims
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * Turns a request's credentials into a principal (RFC 0006 §5.3.2). Replaces `SecurityContextParser` at the switch.
 *
 * Must not block the calling thread, which may be an event loop: an implementation that needs a blocking client moves
 * the call to its own bounded executor (§7.7). Report a failed dependency as [AuthnResult.Unavailable] rather than by
 * throwing; the pipeline treats a thrown exception the same way, and both deny with 503.
 */
fun interface Authenticator {
    suspend fun authenticate(credentials: Credentials): AuthnResult
}

/**
 * The credentials of a request, read by the pipeline the way `DefaultSecurityContextParser` reads them: the token from
 * the `Authorization` header, else (when allowed) the query parameter of that name, else the cookie; the API key from
 * its own header. Null when absent.
 */
class Credentials(val token: String?, val apiKey: String?) {
    val isEmpty: Boolean get() = token == null && apiKey == null

    override fun toString(): String = "Credentials(token=${token?.let { "***" }}, apiKey=${apiKey?.let { "***" }})"
}

sealed interface AuthnResult {
    /** [method] names how, such as `jwt` or `apikey`; it goes to audit. */
    class Authenticated(val principal: AuthenticatedPrincipal, val method: String) : AuthnResult

    /** The request carries no credentials. */
    data object Anonymous : AuthnResult

    /**
     * The credentials are not valid. The request is evaluated as anonymous; a deny then answers 401 with [reason]
     * (RFC 0006 K2, Q2).
     */
    class Rejected(val reason: RejectReason) : AuthnResult

    /** A dependency the authenticator needs failed, such as the API key store: 503. */
    class Unavailable(val cause: Throwable) : AuthnResult

    enum class RejectReason { TOKEN_INVALID, TOKEN_EXPIRED, TOKEN_REVOKED, API_KEY_INVALID }
}

/** Who the credentials prove the caller is. */
class AuthenticatedPrincipal(
    val id: PrincipalId,
    val tenant: TenantId,
    /** Where the principal's memberships come from (RFC 0004 §4.5). */
    val membershipSource: MembershipSource,
    val attributes: Map<String, Any> = emptyMap(),
) {
    /**
     * [memberships] are the claims of the token (RFC 0004 §4.5.1); null when the token asks for the
     * `MembershipProvider` (§4.5.2).
     */
    constructor(
        id: PrincipalId,
        tenant: TenantId,
        memberships: Memberships?,
        attributes: Map<String, Any> = emptyMap(),
    ) : this(id, tenant, memberships?.let(MembershipSource::Claims) ?: MembershipSource.Provider, attributes)

    /** The claimed memberships; null when they come from the provider or cannot be used. */
    val memberships: Memberships? get() = (membershipSource as? MembershipSource.Claims)?.memberships
}

/** Where a principal's memberships come from (RFC 0004 §4.5). */
sealed interface MembershipSource {
    /** The token's `roles` and `policies` claims (§4.5.1). */
    class Claims(val memberships: Memberships) : MembershipSource {
        init {
            require(memberships.source == Memberships.Source.CLAIMS) {
                "Claimed memberships must have the source CLAIMS, not ${memberships.source}."
            }
        }
    }

    /** The token carries the provider marker: ask the `MembershipProvider` (§4.5.2). */
    data object Provider : MembershipSource

    /**
     * The token's membership claims cannot be used, such as an unknown marker or a marker together with claims
     * (`MEM-1`). The request fails closed with `Indeterminate(MembershipUnavailable)`, never with no memberships.
     */
    class Unusable(val reason: String) : MembershipSource

    companion object {
        /**
         * The source a verified token states (RFC 0004 §4.5): no marker means its claims; the provider marker alone
         * means the provider; any other marker, or the marker together with `roles` or `policies` claims, is unusable.
         */
        fun of(principal: CoSecPrincipal): MembershipSource {
            val marker = MembershipClaims.markerOf(principal)
                ?: return Claims(MembershipClaims.parse(principal.roles, principal.policies))
            return when {
                marker != MembershipClaims.PROVIDER ->
                    Unusable("The token of principal [${principal.id}] has an unknown membership marker [$marker].")

                principal.roles.isNotEmpty() || principal.policies.isNotEmpty() -> Unusable(
                    "The token of principal [${principal.id}] asks for a membership provider but also claims roles or policies."
                )

                else -> Provider
            }
        }
    }
}
