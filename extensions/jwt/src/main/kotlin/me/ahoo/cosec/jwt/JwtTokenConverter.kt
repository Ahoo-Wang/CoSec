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
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.PolicyCapable
import me.ahoo.cosec.api.principal.RoleCapable
import me.ahoo.cosec.api.tenant.Tenant.Companion.TENANT_ID_KEY
import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.runtime.token.SimpleCompositeToken
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosid.StringIdGenerator
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.*

/**
 * JWT-based token converter.
 *
 * This converter generates JWT tokens containing principal information
 * including policies, roles, and tenant context.
 *
 * @param idGenerator Generator for unique token IDs
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
    private val idGenerator: StringIdGenerator,
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

    override fun toToken(principal: CoSecPrincipal): CompositeToken = toToken(
        principal,
        accessTokenValidity,
        refreshTokenValidity
    )

    override fun toToken(
        principal: CoSecPrincipal,
        accessTokenValidity: Duration,
        refreshTokenValidity: Duration
    ): CompositeToken {
        val accessTokenId = idGenerator.generateAsString()
        val now = Date()
        val accessTokenExp = Date(System.currentTimeMillis() + accessTokenValidity.toMillis())
        val accessTokenBuilder =
            JWT
                .create()
                .withJWTId(accessTokenId)
                .withSubject(principal.id)
                .withIssuedAt(now)
                .withExpiresAt(accessTokenExp)
        writeMemberships(accessTokenBuilder, principal)
        if (principal.attributes.isNotEmpty()) {
            accessTokenBuilder.withClaim(CoSecPrincipal::attributes.name, principal.attributes)
        }

        if (principal is TenantCapable) {
            val tenantCapable = principal as TenantCapable
            accessTokenBuilder
                .withClaim(TENANT_ID_KEY, tenantCapable.tenant.tenantId)
        }
        val accessToken = accessTokenBuilder.sign(algorithm)
        val refreshTokenId = idGenerator.generateAsString()
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
     * A principal parsed from a token with the membership marker keeps it, along with whatever `roles` and `policies`
     * that token had, so that refreshing never turns "ask the provider" (or an invalid marker) into "no memberships".
     */
    private fun writeMemberships(accessTokenBuilder: JWTCreator.Builder, principal: CoSecPrincipal) {
        val marker = MembershipClaims.markerOf(principal)
        if (marker == null && exceedsMembershipClaimLimit(principal)) {
            accessTokenBuilder.withClaim(MembershipClaims.MARKER_KEY, MembershipClaims.PROVIDER)
            return
        }
        if (marker != null) {
            accessTokenBuilder.withClaim(MembershipClaims.MARKER_KEY, marker)
        }
        if (principal.policies.isNotEmpty()) {
            accessTokenBuilder.withClaim(PolicyCapable.POLICY_KEY, principal.policies.toList())
        }
        if (principal.roles.isNotEmpty()) {
            accessTokenBuilder.withClaim(RoleCapable.ROLE_KEY, principal.roles.toList())
        }
    }

    private fun exceedsMembershipClaimLimit(principal: CoSecPrincipal): Boolean {
        val limit = membershipClaimLimit ?: return false
        return claimSize(principal.policies) + claimSize(principal.roles) > limit
    }

    private fun claimSize(values: Set<String>): Int =
        if (values.isEmpty()) 0 else jsonMapper.writeValueAsBytes(values.toList()).size
}
