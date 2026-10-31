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

package me.ahoo.cosec.spi.authn

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipClaims
import java.time.Instant

/**
 * Turns a request's credentials into a principal (RFC 0006 §5.3.2).
 *
 * Must not block the calling thread, which may be an event loop: an implementation that needs a blocking client moves
 * the call to its own bounded executor (§7.7). Report a failed dependency as [AuthnResult.Unavailable] rather than by
 * throwing; the pipeline treats a thrown exception the same way, and both deny with 503.
 */
@CoSecSpi
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
     * (RFC 0006 K2, Q2). [refused] names whose credential it was, when that is known (a disabled key, a principal the
     * membership provider no longer knows): for audit, never as the subject.
     */
    class Rejected(val reason: RejectReason, val refused: RefusedCredential? = null) : AuthnResult

    /** Whose credential was refused, and which one (a token's `jti`, an API key's id), for audit only. */
    class RefusedCredential(val tenant: TenantId, val principal: PrincipalId, val credentialId: String?) {
        override fun toString(): String = "${principal.value}@${tenant.value}" + (credentialId?.let { " ($it)" } ?: "")
    }

    /** A dependency the authenticator needs failed, such as the API key store: 503. */
    class Unavailable(val cause: Throwable) : AuthnResult

    enum class RejectReason {
        TOKEN_INVALID,
        TOKEN_EXPIRED,
        TOKEN_REVOKED,
        API_KEY_INVALID,

        /** A token that delegates (an `act` claim, RFC 0009 §6): invalid to the client, named in audit (`IDN-4`). */
        DELEGATION_UNSUPPORTED,
    }
}

/**
 * Who the credentials prove the caller is.
 *
 * [Identity.Method.JWT] names CoSec's own token and nothing else: a principal whose [credential] has that method is made
 * only through the internal constructor, by CoSec's token authenticator, which also sets [breakGlass]. The public
 * constructors refuse it, so no other authenticator can be taken for CoSec's token by its name (RC1-26).
 */
class AuthenticatedPrincipal @InternalCoSecApi constructor(
    val id: PrincipalId,
    val tenant: TenantId,
    /** Where the principal's memberships come from (RFC 0004 §4.5). */
    val membershipSource: MembershipSource,
    val attributes: Map<String, Any>,
    /** The credential that proved it, by id and expiry, never its value: a gateway forwards it with the identity. */
    val credential: Identity.Credential?,
    /**
     * Whether the principal's break-glass bindings may apply: only for a principal CoSec's own token verifier
     * verified, by provenance, never by a name a credential carries. So an API key, a token another verifier accepted,
     * a credential of another authenticator, whatever it is called, and a principal without a credential never get it
     * (RFC 0009 §4.2, `BRK-8`). It goes with [Identity.Method.JWT], which a gateway forwards for CoSec's tokens only.
     */
    val breakGlass: Boolean,
    /**
     * When the credential was issued (a token's `iat`), so that a later revocation of the principal's tokens can be told
     * apart from an earlier one: CoSec's token authenticator sets it, an approval records it with the credential's id
     * (RC1-11). Null when the credential does not say, which a revocation of the principal's tokens takes as revoked:
     * an approval by such a principal is taken back by any revocation of its tokens.
     */
    val issuedAt: Instant?,
    /** Set by CoSec's API key authenticator: by provenance, as [breakGlass] ([apiKey]). */
    private val apiKeyCredential: Boolean,
) {
    init {
        require(breakGlass == (credential?.method == Identity.Method.JWT)) {
            "The authentication method [${Identity.Method.JWT}] is CoSec's own token's, and only it may use " +
                "break-glass: name another authenticator's method otherwise (RFC 0010 C3)."
        }
    }

    /** A principal another authenticator proved: never break-glass. */
    @OptIn(InternalCoSecApi::class)
    constructor(
        id: PrincipalId,
        tenant: TenantId,
        membershipSource: MembershipSource,
        attributes: Map<String, Any> = emptyMap(),
        credential: Identity.Credential? = null,
    ) : this(
        id,
        tenant,
        membershipSource,
        attributes,
        credential,
        breakGlass = false,
        issuedAt = null,
        apiKeyCredential = false
    )

    /**
     * [memberships] are the claims of the token (RFC 0004 §4.5.1); null when the token asks for the
     * `MembershipProvider` (§4.5.2).
     */
    @OptIn(InternalCoSecApi::class)
    constructor(
        id: PrincipalId,
        tenant: TenantId,
        memberships: Memberships?,
        attributes: Map<String, Any> = emptyMap(),
        credential: Identity.Credential? = null,
    ) : this(
        id,
        tenant,
        memberships?.let(MembershipSource::Claims) ?: MembershipSource.Provider.UNCAPPED,
        attributes,
        credential,
        breakGlass = false,
        issuedAt = null,
        apiKeyCredential = false,
    )

    /**
     * Whether an API key proved the principal, a workload's credential: so CoSec's API key authenticator says, by
     * provenance, and so does any credential named [Identity.Method.API_KEY], a gateway's forwarded identity among them
     * (fail closed). An API key never approves a change and is never the control plane's trust root (RC1-11); a host's
     * own authenticator for workloads should not be given `cosec:approve` either.
     */
    val apiKey: Boolean get() = apiKeyCredential || credential?.method == Identity.Method.API_KEY

    /** The claimed memberships; null when they come from the provider or cannot be used. */
    val memberships: Memberships? get() = (membershipSource as? MembershipSource.Claims)?.memberships

    /**
     * Whether what is bound to the principal by name grants it anything: not for a credential with a role cap (RFC 0009
     * §4.2), whose cap is in [membershipSource] or, behind a gateway, in [credential]. The DENYs bound to it still apply.
     */
    val principalBindings: Boolean
        get() = (membershipSource as? MembershipSource.Provider)?.cap == null && credential?.capped != true
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

    /**
     * Ask the `MembershipProvider` (§4.5.2): a token that carries the provider marker, or an API key (RFC 0009 §4.2).
     * The credential is refused when the provider does not know the principal or says it is not active (`IDN-3`).
     *
     * [cap], when not null, only narrows what the provider answers: the roles outside it are dropped, matched exactly
     * by space and role (`IDN-2`). An empty cap leaves no roles; null leaves the memberships as they are. The directly
     * attached policies stay, for their DENYs: a cap is expressed in roles, so they grant a capped credential nothing,
     * and restrict it as they restrict its principal (RC1-1).
     */
    class Provider(val cap: Map<SpaceId, Set<RoleId>>? = null) : MembershipSource {
        companion object {
            /** The provider's memberships as they are. */
            val UNCAPPED: Provider = Provider()

            /** A cap of [roles], written as in claims and in documents: `role`, or `role@space`. */
            fun capped(roles: Collection<String>): Provider = Provider(MembershipClaims.parse(roles, emptyList()).roles)
        }
    }

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

                else -> Provider.UNCAPPED
            }
        }
    }
}
