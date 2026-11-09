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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.jwt

import com.auth0.jwt.JWT
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.MembershipResolver
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.InMemoryMembershipProvider
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * RFC 0004 §4.5.1: membership claims, the `cosec.membership` marker and the claim-size threshold.
 */
class MembershipClaimsJwtTest {
    private val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM)
    private val tenant = TenantId("tenant")
    private val policies = setOf("p2", "p1")
    private val roles = setOf("admin", "editor@team-a")
    private val attributes = mapOf("department" to "sales")

    private fun converter(limit: Int? = null) = JwtTokenConverter(
        JwtFixture.IDS,
        JwtFixture.ALGORITHM,
        membershipClaimLimit = limit,
    )

    private fun principal(roles: Set<String> = this.roles) =
        TokenSubject("alice", null, tenant = tenant.value, roles = roles, policies = policies, attributes = attributes)

    /** A token as CoSec 7.x writes it. */
    private fun sevenXToken(
        tenantId: String?,
        issuedAt: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    ): String {
        val builder = JWT.create()
            .withJWTId("jti")
            .withSubject("alice")
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(issuedAt.plus(Duration.ofMinutes(10))))
            .withClaim("policies", policies.toList())
            .withClaim("roles", roles.toList())
            .withClaim("attributes", attributes)
        tenantId?.let { builder.withClaim("tenantId", it) }
        return builder.sign(JwtFixture.ALGORITHM)
    }

    private fun claimSize(values: Set<String>) = JsonMapper.builder().build().writeValueAsBytes(values.toList()).size

    @Test
    fun currentTokensParseAsBefore() {
        val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val parsed = verifier.verify(sevenXToken("tenant", issuedAt))

        parsed.tenant.assert().isEqualTo("tenant")
        parsed.id.assert().isEqualTo("jti")
        parsed.principal.assert().isEqualTo("alice")
        parsed.roles.assert().isEqualTo(roles)
        parsed.policies.assert().isEqualTo(policies)
        parsed.attributes.assert().isEqualTo(attributes)
        parsed.issuedAt.assert().isEqualTo(issuedAt)
        parsed.membershipMarker.assert().isNull()
    }

    @Test
    fun currentTokensWithoutTenantParseAsBefore() {
        val parsed = Jwts.toVerifiedToken(Jwts.decode(sevenXToken(tenantId = null)))

        parsed.tenant.assert().isNull()
        Jwts.toVerifiedToken(Jwts.decode(sevenXToken("tenant"))).tenant.assert().isEqualTo(tenant.value)
        parsed.principal.assert().isEqualTo("alice")
        parsed.roles.assert().isEqualTo(roles)
    }

    /** Resolves [parsed] the way the pipeline does: its membership source, then the resolver; every principal is known. */
    private fun resolve(provider: MembershipProvider?, parsed: VerifiedToken): Memberships = runBlocking {
        checkNotNull(
            MembershipResolver(provider).resolve(tenant, PrincipalId(parsed.principal), MembershipSource.of(parsed))
        )
    }

    @Test
    fun currentTokensResolveToTheirClaims() {
        val parsed = Jwts.toVerifiedToken(Jwts.decode(sevenXToken("tenant")))

        val memberships = resolve(null, parsed)

        memberships.source.assert().isEqualTo(Memberships.Source.CLAIMS)
        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("admin"))
        memberships.roles[SpaceId("team-a")]!!.toList().assert().containsExactly(RoleId("editor"))
        memberships.attachments.map { it.value }.assert().containsExactly("p2", "p1")
    }

    @Test
    fun underTheLimitTheConverterWritesTheSameClaims() {
        listOf(null, JwtTokenConverter.DEFAULT_MEMBERSHIP_CLAIM_LIMIT, claimSize(policies) + claimSize(roles)).forEach {
            val decoded = Jwts.decode(converter(it).toToken(principal()).accessToken)

            decoded.claims.keys.assert().containsExactlyInAnyOrder(
                "jti",
                "sub",
                "iat",
                "exp",
                "policies",
                "roles",
                "attributes",
                "tenantId"
            )
            decoded.getClaim("policies").asList(String::class.java).assert().containsExactly("p2", "p1")
            decoded.getClaim("roles").asList(String::class.java).assert().containsExactly("admin", "editor@team-a")
        }
    }

    @Test
    fun aboveTheLimitMembershipsMoveToTheProvider() {
        val limit = claimSize(policies) + claimSize(roles) - 1
        val token = converter(limit).toToken(principal())
        val decoded = Jwts.decode(token.accessToken)

        decoded.claims.keys.assert().doesNotContain("policies", "roles")
        decoded.getClaim(MembershipClaims.MARKER_KEY).asString().assert().isEqualTo(MembershipClaims.PROVIDER)
        decoded.getClaim("attributes").asMap().assert().isEqualTo(attributes)

        val parsed = verifier.verify(token.accessToken)
        parsed.roles.assert().isEmpty()
        parsed.policies.assert().isEmpty()
        parsed.principal.assert().isEqualTo("alice")

        val provider = InMemoryMembershipProvider()
        val fromProvider =
            Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to setOf(RoleId("admin"))), emptyList())
        provider.put(tenant, PrincipalId("alice"), fromProvider)
        resolve(provider, parsed).assert().isSameAs(fromProvider)

        assertThrows<MembershipUnavailableException> { resolve(null, parsed) }
    }

    @Test
    fun onlyRolesCountWhenThereAreNoPolicies() {
        val principal = TokenSubject("alice", null, roles = roles)
        val decoded = Jwts.decode(converter(claimSize(roles)).toToken(principal).accessToken)

        decoded.getClaim("roles").asList(String::class.java).assert().containsExactly("admin", "editor@team-a")
        decoded.claims.keys.assert().doesNotContain("policies", MembershipClaims.MARKER_KEY)
    }

    @Test
    fun theDefaultNeverMoves() {
        val manyRoles = (0 until 2_000).map { "role-$it@space-$it" }.toSet()
        val decoded = Jwts.decode(converter().toToken(principal(manyRoles)).accessToken)

        decoded.getClaim("roles").asList(String::class.java).assert().hasSize(manyRoles.size)
        decoded.getClaim(MembershipClaims.MARKER_KEY).isMissing.assert().isTrue()
    }

    @Test
    fun refreshingKeepsTheMarker() {
        val parsed = verifier.verify(converter(0).toToken(principal()).accessToken)

        // no limit on the second converter: the marker carries over by itself, not by size
        val decoded = Jwts.decode(converter().toToken(TokenSubject.of(parsed)).accessToken)

        decoded.getClaim(MembershipClaims.MARKER_KEY).asString().assert().isEqualTo(MembershipClaims.PROVIDER)
        decoded.claims.keys.assert().doesNotContain("policies", "roles")
    }

    @Test
    fun refreshingKeepsAnInvalidMarkerAndItsClaims() {
        val marked = TokenSubject("alice", null, roles = roles, policies = policies, membershipMarker = "claims")

        val decoded = Jwts.decode(converter(0).toToken(marked).accessToken)

        decoded.getClaim(MembershipClaims.MARKER_KEY).asString().assert().isEqualTo("claims")
        decoded.getClaim("roles").asList(String::class.java).assert().containsExactly("admin", "editor@team-a")
    }

    @Test
    fun aMarkerThatIsNotAStringIsKeptAndRefused() {
        val now = Instant.now()
        listOf<Pair<Any?, String>>(1 to "1", null to "null", true to "true").forEach { (value, text) ->
            val builder = JWT.create().withJWTId(
                "jti"
            ).withSubject("alice").withExpiresAt(Date.from(now.plusSeconds(60)))
            when (value) {
                null -> builder.withNullClaim(MembershipClaims.MARKER_KEY)
                is Int -> builder.withClaim(MembershipClaims.MARKER_KEY, value)
                is Boolean -> builder.withClaim(MembershipClaims.MARKER_KEY, value)
            }
            val parsed = Jwts.toVerifiedToken(Jwts.decode(builder.sign(JwtFixture.ALGORITHM)))

            parsed.membershipMarker.assert().isEqualTo(text)
            assertThrows<MembershipUnavailableException> { resolve(InMemoryMembershipProvider(), parsed) }
        }
    }

    @Test
    fun theLimitMustNotBeNegative() {
        assertThrows<IllegalArgumentException> { converter(-1) }
    }
}
