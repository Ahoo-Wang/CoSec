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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.scope.ScopeResolver
import java.time.Instant
import java.util.Collections

/**
 * What a verified token says: the one result of [TokenVerifier], an immutable value. A plain class, not a data class, so
 * that a field can be added later without breaking verifiers (RFC 0010 §1).
 *
 * Every verifier makes one, so the rules of a token's claims hold here, whichever verifier read it, on every path that
 * verifies a token (a request, a refresh, a logout). A token that breaks one is invalid, and the constructor throws
 * [TokenVerificationException], the verifier's own way of saying so:
 *
 * - [principal] is not empty and does not begin with `(`, which is CoSec's, as for tenants and spaces (`IDN-12`);
 * - [tenant], when the token has one, is not empty (`IDN-11`): mapped to the default tenant, it would move the principal;
 * - [app], when the token declares one, can be an app id (`SCP-10`);
 * - no id holds an unpaired UTF-16 surrogate (RC1-52, `IDN-10`): a Redis key would hold `?` in its place, so the token
 *   would read another principal's memberships and revocations, or another tenant's documents.
 *
 * [roles], [policies] and [attributes] are read-only copies, in the order given.
 */
class VerifiedToken(
    /** The token's id (`jti`), what a revocation names. */
    val id: String,
    /** Whom the token is for (`sub`). */
    val principal: String,
    /** The token's tenant, null when it has no tenant claim: it is then in the default tenant. */
    val tenant: String?,
    /** The app the token is for (`aud`, RFC 0012 §3.5), null when it declares none. No default: a verifier says which. */
    val app: String?,
    roles: Set<String> = emptySet(),
    policies: Set<String> = emptySet(),
    attributes: Map<String, Any> = emptyMap(),
    /** When the token was issued (`iat`), null when it does not say; a revocation of its principal's tokens reads it. */
    val issuedAt: Instant? = null,
    /** When the token expires (`exp`), null when it does not say; a gateway forwards it with the identity. */
    val expiresAt: Instant? = null,
    /**
     * The `cosec.membership` claim, null when the token has none: [MembershipClaims.PROVIDER][me.ahoo.cosec.spi.membership.MembershipClaims.PROVIDER],
     * or anything else the token said (a value that is not a string is kept as its JSON text), which the pipeline refuses.
     */
    val membershipMarker: String? = null,
) {
    /** The `roles` claim: `role`, or `role@space`. */
    val roles: Set<String> = Collections.unmodifiableSet(LinkedHashSet(roles))

    /** The `policies` claim. */
    val policies: Set<String> = Collections.unmodifiableSet(LinkedHashSet(policies))

    /** The `attributes` claim. */
    val attributes: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(attributes))

    init {
        when {
            id.isEmpty() -> invalid("has no id")
            principal.isEmpty() -> invalid("has no subject")
            Identifiers.isReserved(principal) -> invalid("names a subject reserved for CoSec")
            tenant != null && tenant.isEmpty() -> invalid("names an empty tenant")
            app != null && !ScopeResolver.isValidAppId(app) -> invalid("declares an app that cannot be an app id")
            !wellFormed() -> invalid("claims an id with an unpaired UTF-16 surrogate")
        }
    }

    private fun wellFormed(): Boolean =
        Identifiers.isWellFormed(id) &&
            Identifiers.isWellFormed(principal) &&
            (tenant == null || Identifiers.isWellFormed(tenant)) &&
            Identifiers.areWellFormed(roles) &&
            Identifiers.areWellFormed(policies)

    private fun invalid(reason: String): Nothing = throw TokenVerificationException("The token $reason.")

    override fun toString(): String = "VerifiedToken(id=$id, principal=$principal, tenant=$tenant, app=$app)"
}
