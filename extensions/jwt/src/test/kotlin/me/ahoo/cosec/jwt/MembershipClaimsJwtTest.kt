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
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.spi.membership.InMemoryMembershipProvider
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipMarkedPrincipal
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.runtime.MembershipResolver
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.runtime.token.SimpleAccessToken
import me.ahoo.cosec.runtime.token.SimpleTokenPrincipal
import me.ahoo.cosec.runtime.token.SimpleTokenTenantPrincipal
import me.ahoo.cosid.test.MockIdGenerator
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
        MockIdGenerator.INSTANCE,
        JwtFixture.ALGORITHM,
        membershipClaimLimit = limit,
    )

    private fun principal(roles: Set<String> = this.roles) =
        SimpleTenantPrincipal(SimplePrincipal("alice", policies, roles, attributes), SimpleTenant(tenant.value))

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

    private fun TokenPrincipal.innermost(): CoSecPrincipal {
        val tokenPrincipal = (this as? SimpleTokenTenantPrincipal)?.delegate ?: this
        return (tokenPrincipal as SimpleTokenPrincipal).delegate
    }

    @Test
    fun currentTokensParseAsBefore() {
        val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val parsed = verifier.verify<TokenPrincipal>(SimpleAccessToken(sevenXToken("tenant", issuedAt)))

        parsed.assert().isInstanceOf(SimpleTokenTenantPrincipal::class.java)
        (parsed as SimpleTokenTenantPrincipal).tenant.assert().isEqualTo(SimpleTenant("tenant"))
        parsed.tokenId.assert().isEqualTo("jti")
        // exactly the principal 7.x built: no wrapper, so equality and type checks behave as before
        parsed.innermost().assert().isEqualTo(SimplePrincipal("alice", policies, roles, attributes))
        parsed.innermost().javaClass.assert().isEqualTo(SimplePrincipal::class.java)
        (parsed.delegate as SimpleTokenPrincipal).issuedAt.assert().isEqualTo(issuedAt)
        MembershipClaims.markerOf(parsed).assert().isNull()
    }

    @Test
    fun currentTokensWithoutTenantParseAsBefore() {
        val parsed = Jwts.toPrincipal<TokenPrincipal>(Jwts.decode(sevenXToken(tenantId = null)))

        parsed.javaClass.assert().isEqualTo(SimpleTokenPrincipal::class.java)
        MembershipClaims.tenantOf(parsed).assert().isEqualTo(TenantId.DEFAULT)
        MembershipClaims.tenantOf(Jwts.toPrincipal<TokenPrincipal>(Jwts.decode(sevenXToken(tenantId = ""))))
            .assert().isEqualTo(TenantId.DEFAULT)
        MembershipClaims.tenantOf(
            Jwts.toPrincipal<TokenPrincipal>(Jwts.decode(sevenXToken("tenant")))
        ).assert().isEqualTo(tenant)
        parsed.innermost().assert().isEqualTo(SimplePrincipal("alice", policies, roles, attributes))
    }

    /** Resolves [parsed] the way the pipeline does: its membership source, then the resolver. */
    private fun resolve(provider: MembershipProvider?, parsed: TokenPrincipal): Memberships = runBlocking {
        MembershipResolver(provider).resolve(tenant, PrincipalId(parsed.id), MembershipSource.of(parsed))
    }

    @Test
    fun currentTokensResolveToTheirClaims() {
        val parsed = Jwts.toPrincipal<TokenPrincipal>(Jwts.decode(sevenXToken("tenant")))

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

        val parsed = verifier.verify<TokenPrincipal>(token)
        parsed.roles.assert().isEmpty()
        parsed.policies.assert().isEmpty()
        parsed.id.assert().isEqualTo("alice")
        parsed.innermost().assert().isInstanceOf(MembershipMarkedPrincipal::class.java)

        val provider = InMemoryMembershipProvider()
        val fromProvider =
            Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to setOf(RoleId("admin"))), emptyList())
        provider.put(tenant, PrincipalId("alice"), fromProvider)
        resolve(provider, parsed).assert().isSameAs(fromProvider)

        assertThrows<MembershipUnavailableException> { resolve(null, parsed) }
    }

    @Test
    fun onlyRolesCountWhenThereAreNoPolicies() {
        val principal = SimplePrincipal("alice", roles = roles)
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
        val parsed = verifier.verify<TokenPrincipal>(converter(0).toToken(principal()))

        // no limit on the second converter: the marker carries over by itself, not by size
        val decoded = Jwts.decode(converter().toToken(parsed).accessToken)

        decoded.getClaim(MembershipClaims.MARKER_KEY).asString().assert().isEqualTo(MembershipClaims.PROVIDER)
        decoded.claims.keys.assert().doesNotContain("policies", "roles")
    }

    @Test
    fun refreshingKeepsAnInvalidMarkerAndItsClaims() {
        val marked = MembershipMarkedPrincipal(SimplePrincipal("alice", policies, roles), "claims")

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
            val parsed = Jwts.toPrincipal<TokenPrincipal>(Jwts.decode(builder.sign(JwtFixture.ALGORITHM)))

            MembershipClaims.markerOf(parsed).assert().isEqualTo(text)
            assertThrows<MembershipUnavailableException> { resolve(InMemoryMembershipProvider(), parsed) }
        }
    }

    @Test
    fun theLimitMustNotBeNegative() {
        assertThrows<IllegalArgumentException> { converter(-1) }
    }
}
