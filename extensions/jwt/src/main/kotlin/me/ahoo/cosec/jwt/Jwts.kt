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
package me.ahoo.cosec.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.RegisteredClaims
import com.auth0.jwt.interfaces.DecodedJWT
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.DelegationUnsupportedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.VerifiedToken

/**
 * What [JwtTokenVerifier] needs to read a token: decoding, which reads a token without checking its signature, and
 * reading the claims of a token. Internal: only the verifier, after it verified the signature, may read what a token
 * says (`PIP-11`, `UnverifiedTokenTest`).
 */
internal object Jwts {
    const val TOKEN_PREFIX = "Bearer "

    /** The RFC 8693 actor claim. `may_act` only says who may act for the subject, and does not change it. */
    const val ACTOR_CLAIM = "act"
    private val jwtParser = JWT()

    fun String.removeBearerPrefix(): String =
        if (this.startsWith(TOKEN_PREFIX)) {
            this.substring(TOKEN_PREFIX.length)
        } else {
            this
        }

    fun decode(token: String): DecodedJWT {
        val jwtToken = token.removeBearerPrefix()
        return jwtParser.decodeJwt(jwtToken)
    }

    /**
     * What [decodedAccessToken] says; the caller has verified its signature. A delegation token (an `act` claim) never
     * says anything: read as its `sub`, it would give the actor everything the subject may do (RFC 0009 §6, `IDN-4`).
     * A token with no `sub` or `jti` is invalid.
     */
    fun toVerifiedToken(decodedAccessToken: DecodedJWT): VerifiedToken {
        if (!decodedAccessToken.getClaim(ACTOR_CLAIM).isMissing) {
            throw DelegationUnsupportedException(
                "The token delegates (it carries an `act` claim), which CoSec does not support."
            )
        }
        return VerifiedToken(
            id = required(decodedAccessToken.id, "id"),
            principal = required(decodedAccessToken.subject, "subject"),
            tenant = tenantOf(decodedAccessToken),
            app = appOf(decodedAccessToken),
            roles = strings(decodedAccessToken, MembershipClaims.ROLES_KEY),
            policies = strings(decodedAccessToken, MembershipClaims.POLICIES_KEY),
            attributes = attributesOf(decodedAccessToken),
            issuedAt = decodedAccessToken.issuedAtAsInstant,
            expiresAt = decodedAccessToken.expiresAtAsInstant,
            membershipMarker = membershipMarkerOf(decodedAccessToken),
        )
    }

    private fun required(claim: String?, name: String): String =
        claim ?: throw TokenVerificationException("The token has no $name.")

    /**
     * The strings of the array claim [name], empty when the token has none. A claim that is there but is not an array of
     * strings (`null`, a string, an object, an array holding a number) makes the token invalid (401): read as empty, a
     * malformed `roles` claim would quietly drop the roles a DENY is bound to, and it used to fail as a 500.
     */
    private fun strings(decodedAccessToken: DecodedJWT, name: String): Set<String> {
        val claim = decodedAccessToken.getClaim(name)
        if (claim.isMissing) {
            return emptySet()
        }
        val values = claim.asList(Any::class.java)
            ?: throw TokenVerificationException("The token's [$name] claim is not an array.")
        return values.mapTo(LinkedHashSet()) {
            it as? String ?: throw TokenVerificationException("The token's [$name] claim holds a value that is not a string.")
        }
    }

    /** The token's `attributes` claim, empty when it has none; one that is not an object makes the token invalid (401). */
    private fun attributesOf(decodedAccessToken: DecodedJWT): Map<String, Any> {
        val name = MembershipClaims.ATTRIBUTES_KEY
        val claim = decodedAccessToken.getClaim(name)
        if (claim.isMissing) {
            return emptyMap()
        }
        return claim.asMap() ?: throw TokenVerificationException("The token's [$name] claim is not an object.")
    }

    /**
     * The token's `tenantId` claim, null when it has none. A claim that is there but not a string (a number, `null`,
     * an object, RC1-22) or empty (`IDN-11`) is an invalid token: read as no tenant, it would put the principal in the
     * default tenant `(0)` with the token's roles, and out of reach of a revocation of its real tenant.
     */
    private fun tenantOf(decodedAccessToken: DecodedJWT): String? {
        val claim = decodedAccessToken.getClaim(MembershipClaims.TENANT_KEY)
        if (claim.isMissing) {
            return null
        }
        val tenantId = claim.asString() ?: throw TokenVerificationException("The token's [${MembershipClaims.TENANT_KEY}] claim is not a string.")
        if (tenantId.isEmpty()) {
            throw TokenVerificationException("The token's [${MembershipClaims.TENANT_KEY}] claim is empty.")
        }
        return tenantId
    }

    /**
     * The app the token declares (RFC 0012 §3.5), null when it has no `aud` claim. One value, as a string or as an array
     * of one string, the two forms RFC 7519 gives it. Anything else (several values, none, `null`, a value that is not a
     * string) makes the token invalid: read as no app, it would pass where an app is not required, and
     * [DecodedJWT.getAudience] does read a number as none.
     */
    private fun appOf(decodedAccessToken: DecodedJWT): String? {
        val claim = decodedAccessToken.getClaim(RegisteredClaims.AUDIENCE)
        if (claim.isMissing) {
            return null
        }
        claim.asString()?.let { return it }
        val values = claim.asList(Any::class.java)
            ?: throw TokenVerificationException("The token's [aud] claim is neither a string nor an array.")
        return values.singleOrNull() as? String
            ?: throw TokenVerificationException("The token's [aud] claim is not one string.")
    }

    /**
     * The value of [MembershipClaims.MARKER_KEY], null when the token has none. Any value is kept, including one that is
     * not a string, for the pipeline to refuse (`MembershipSource.Unusable` in `cosec-spi`).
     */
    private fun membershipMarkerOf(decodedAccessToken: DecodedJWT): String? {
        val claim = decodedAccessToken.getClaim(MembershipClaims.MARKER_KEY)
        return when {
            claim.isMissing -> null
            claim.isNull -> "null"
            else -> claim.asString() ?: claim.toString()
        }
    }
}
