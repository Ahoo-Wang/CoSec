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
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.membership.MembershipClaims
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.*

/**
 * JWT-based token converter.
 *
 * This converter generates JWT tokens containing what a [TokenSubject] says: its policies, roles, tenant and app.
 *
 * @param idGenerator the token ids (`jti`), such as [me.ahoo.cosec.spi.id.RandomIdGenerator]; they need not be
 * unpredictable, since every token is signed
 * @param algorithm The algorithm used to sign JWTs
 * @param accessTokenValidity Duration for which access tokens are valid
 * @param refreshTokenValidity Duration for which refresh tokens are valid
 * @param membershipClaimLimit the most bytes the `roles` and `policies` claims may take, serialized as JSON, before
 * the access token carries `cosec.membership: "provider"` instead of them (RFC 0004 §4.5.1); `null`, the default,
 * never moves them. Set it only together with a [me.ahoo.cosec.spi.membership.MembershipProvider] that knows the
 * memberships: a token that carries the marker has no memberships of its own, and without a provider its requests
 * fail closed (503). [DEFAULT_MEMBERSHIP_CLAIM_LIMIT] is a suggested value, not the default.
 * @see TokenConverter
 * @see JwtTokenVerifier
 */
class JwtTokenConverter(
    private val idGenerator: IdGenerator,
    private val algorithm: Algorithm,
    private val accessTokenValidity: Duration = Duration.ofMinutes(10),
    private val refreshTokenValidity: Duration = Duration.ofDays(7),
    private val membershipClaimLimit: Int? = null
) : TokenConverter {
    companion object {
        /** RFC 0001 §7.1: 4 KB. */
        const val DEFAULT_MEMBERSHIP_CLAIM_LIMIT = 4096

        private val jsonMapper = JsonMapper.builder().build()
    }

    init {
        require(membershipClaimLimit == null || membershipClaimLimit >= 0) {
            "The membership claim limit must not be negative, not $membershipClaimLimit."
        }
    }

    override fun toToken(subject: TokenSubject): CompositeToken = toToken(
        subject,
        accessTokenValidity,
        refreshTokenValidity
    )

    override fun toToken(
        subject: TokenSubject,
        accessTokenValidity: Duration,
        refreshTokenValidity: Duration
    ): CompositeToken {
        checkIssuable(subject)
        val accessTokenId = idGenerator.generate()
        val now = Date()
        val accessTokenExp = Date(System.currentTimeMillis() + accessTokenValidity.toMillis())
        val accessTokenBuilder =
            JWT
                .create()
                .withJWTId(accessTokenId)
                .withSubject(subject.id)
                .withIssuedAt(now)
                .withExpiresAt(accessTokenExp)
        subject.app?.let { accessTokenBuilder.withAudience(it) }
        writeMemberships(accessTokenBuilder, subject)
        if (subject.attributes.isNotEmpty()) {
            accessTokenBuilder.withClaim(MembershipClaims.ATTRIBUTES_KEY, subject.attributes)
        }
        subject.tenant?.let { accessTokenBuilder.withClaim(MembershipClaims.TENANT_KEY, it) }
        val accessToken = accessTokenBuilder.sign(algorithm)
        val refreshTokenId = idGenerator.generate()
        val refreshTokenExp = Date(System.currentTimeMillis() + refreshTokenValidity.toMillis())
        val refreshToken =
            JWT
                .create()
                .withJWTId(refreshTokenId)
                .withSubject(accessTokenId)
                .withIssuedAt(now)
                .withExpiresAt(refreshTokenExp)
                .sign(algorithm)
        return SimpleCompositeToken(accessToken, refreshToken)
    }

    /**
     * What a verified token would refuse (`VerifiedToken`), and what [TokenAuthenticator] would, refused where the token
     * is issued instead of when it is presented. In particular java-jwt writes an unpaired surrogate as `?`, so the
     * token would be another principal's, another tenant's, or carry another value (RC1-52, `IDN-10`).
     */
    private fun checkIssuable(subject: TokenSubject) {
        val printableId = Identifiers.printable(subject.id)
        require(subject.id.isNotEmpty()) { "A token has a subject." }
        require(!Identifiers.isReserved(subject.id)) {
            "Principal [$printableId] begins with `${Identifiers.RESERVED_PREFIX}`, which is CoSec's: a token cannot carry it."
        }
        require(subject.tenant?.isEmpty() != true) {
            "Principal [$printableId] has an empty tenant id: a token cannot carry it."
        }
        require(subject.app?.let(ScopeResolver::isValidAppId) != false) {
            "Principal [$printableId] names an app that cannot be an app id: a token cannot carry it."
        }
        val reserved = subject.attributes.keys.filter { it in TokenAuthenticator.RESERVED_ATTRIBUTES }
        require(reserved.isEmpty()) {
            "Principal [$printableId] has attributes $reserved, which CoSec sets for other credentials: a token " +
                "cannot carry them."
        }
        require(wellFormed(subject)) {
            "Principal [$printableId] holds an unpaired UTF-16 surrogate in its id, tenant, roles, policies or " +
                "attributes: a token cannot carry it."
        }
    }

    /**
     * A subject read from a token with the membership marker keeps it, along with whatever `roles` and `policies` that
     * token had, so that refreshing never turns "ask the provider" (or an invalid marker) into "no memberships".
     */
    private fun writeMemberships(accessTokenBuilder: JWTCreator.Builder, subject: TokenSubject) {
        val marker = subject.membershipMarker
        if (marker == null && exceedsMembershipClaimLimit(subject)) {
            accessTokenBuilder.withClaim(MembershipClaims.MARKER_KEY, MembershipClaims.PROVIDER)
            return
        }
        if (marker != null) {
            accessTokenBuilder.withClaim(MembershipClaims.MARKER_KEY, marker)
        }
        if (subject.policies.isNotEmpty()) {
            accessTokenBuilder.withClaim(MembershipClaims.POLICIES_KEY, subject.policies.toList())
        }
        if (subject.roles.isNotEmpty()) {
            accessTokenBuilder.withClaim(MembershipClaims.ROLES_KEY, subject.roles.toList())
        }
    }

    /** Whether every string the token would carry of [subject] holds no unpaired surrogate. */
    private fun wellFormed(subject: TokenSubject): Boolean =
        Identifiers.isWellFormed(subject.id) &&
            subject.tenant?.let(Identifiers::isWellFormed) != false &&
            Identifiers.areWellFormed(subject.roles) &&
            Identifiers.areWellFormed(subject.policies) &&
            wellFormedClaim(subject.attributes)

    private fun wellFormedClaim(value: Any?): Boolean = when (value) {
        is String -> Identifiers.isWellFormed(value)
        is Map<*, *> -> value.all { (name, item) -> wellFormedClaim(name) && wellFormedClaim(item) }
        is Iterable<*> -> value.all(::wellFormedClaim)
        is Array<*> -> value.all(::wellFormedClaim)
        else -> true
    }

    private fun exceedsMembershipClaimLimit(subject: TokenSubject): Boolean {
        val limit = membershipClaimLimit ?: return false
        return claimSize(subject.policies) + claimSize(subject.roles) > limit
    }

    private fun claimSize(values: Set<String>): Int =
        if (values.isEmpty()) 0 else jsonMapper.writeValueAsBytes(values.toList()).size
}
