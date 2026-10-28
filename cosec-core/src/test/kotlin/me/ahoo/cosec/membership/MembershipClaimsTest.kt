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

import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.SimpleTokenPrincipal
import me.ahoo.cosec.token.SimpleTokenTenantPrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class MembershipClaimsTest {

    @Test
    fun parseScopesRolesAsRoleScopeDoes() {
        val memberships = MembershipClaims.parse(
            roles = listOf("admin", "editor@team-a", "viewer@team-a", "editor@team-b", "", "@x", "y@", "a@b@c", "z@*"),
            policies = listOf("p2", "p1"),
        )

        memberships.source.assert().isEqualTo(Memberships.Source.CLAIMS)
        memberships.roles.keys.toList().assert().containsExactly(SpaceId.DEFAULT, SpaceId("team-a"), SpaceId("team-b"))
        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("admin"))
        memberships.roles[SpaceId("team-a")]!!.toList().assert().containsExactly(RoleId("editor"), RoleId("viewer"))
        memberships.roles[SpaceId("team-b")]!!.toList().assert().containsExactly(RoleId("editor"))
        memberships.malformed.assert().containsExactly("", "@x", "y@", "a@b@c", "z@*")
        memberships.attachments.assert().containsExactly(PolicyId("p2"), PolicyId("p1"))
    }

    @Test
    fun parseNothing() {
        val memberships = MembershipClaims.parse(emptyList(), emptyList())

        memberships.roles.assert().isEmpty()
        memberships.attachments.assert().isEmpty()
        memberships.malformed.assert().isEmpty()
    }

    @Test
    fun aRoleClaimedTwiceCountsOnce() {
        val memberships = MembershipClaims.parse(listOf("admin", "admin"), emptyList())

        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("admin"))
    }

    /**
     * RFC 0004 §4.5.1: the same token gives the same roles in every space in 7.x and 8.0, so no token is reissued.
     */
    @Test
    fun rolesInEverySpaceMatchRoleScope() {
        val random = Random(SEED)
        val alphabet = listOf("a", "b", "@", "", "*", " ", "A")
        repeat(ITERATIONS) {
            val roles = List(random.nextInt(0, 6)) {
                List(random.nextInt(0, 5)) { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
            }.toSet()
            val memberships = MembershipClaims.parse(roles, emptyList())
            val spaces = roles.map { it.substringAfter('@', "") }.toSet() + SpaceIdCapable.DEFAULT + "unclaimed"
            spaces.forEach { space ->
                // `*` is no space (RFC 0004 §4.2), so no SpaceId holds it: the one place the claims deliberately give
                // less than 7.x
                val claimed = if (space == "*") {
                    emptyList()
                } else {
                    memberships.roles[SpaceId(space)].orEmpty().map { it.value }
                }
                val expected = if (space == "*") emptySet() else RoleScope.rolesIn(space, roles)
                claimed.assert().describedAs("roles %s in space [%s]", roles, space).containsExactlyElementsOf(expected)
            }
            val malformed = roles.filter { RoleScope.parse(it)?.spaceId?.let { space -> space == "*" } ?: true }
            memberships.malformed.assert().containsExactlyElementsOf(malformed)
        }
    }

    /** Each claimed role lands exactly where `RoleScope.parse` puts it: same strings, no trimming, no case folding. */
    @Test
    fun eachRoleParsesAsRoleScopeParse() {
        val random = Random(SEED)
        val alphabet = listOf("a", "B", "@", "", "*", " ", "\t", "é")
        repeat(ITERATIONS) {
            val role = List(random.nextInt(0, 6)) { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
            val memberships = MembershipClaims.parse(listOf(role), emptyList())
            val spaced = RoleScope.parse(role)
            if (spaced == null || spaced.spaceId == "*") {
                memberships.roles.assert().describedAs("role [%s]", role).isEmpty()
                memberships.malformed.assert().containsExactly(role)
            } else {
                memberships.roles.keys.assert().describedAs("role [%s]", role).containsExactly(SpaceId(spaced.spaceId))
                memberships.roles.getValue(SpaceId(spaced.spaceId)).assert().containsExactly(RoleId(spaced.roleId))
                memberships.malformed.assert().isEmpty()
            }
            memberships.roles.keys.forEach { it.value.assert().doesNotContain("@") }
            memberships.roles.values.forEach { it.assert().isNotEmpty() }
        }
    }

    @Test
    fun theResultIsACopyNobodyCanChange() {
        val roles = mutableListOf("admin", "editor@team-a", "a@b@c")
        val policies = mutableListOf("p1")
        val memberships = MembershipClaims.parse(roles, policies)

        roles.add("viewer")
        policies.add("p2")
        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("admin"))
        memberships.attachments.assert().containsExactly(PolicyId("p1"))

        @Suppress("UNCHECKED_CAST")
        val spaces = memberships.roles as MutableMap<SpaceId, Set<RoleId>>
        assertThrows<UnsupportedOperationException> { spaces.clear() }
        assertThrows<UnsupportedOperationException> {
            (memberships.roles.getValue(SpaceId.DEFAULT) as MutableSet<RoleId>).add(RoleId("root"))
        }
        assertThrows<UnsupportedOperationException> { (memberships.attachments as MutableList<PolicyId>).clear() }
        assertThrows<UnsupportedOperationException> { (memberships.malformed as MutableList<String>).clear() }
    }

    @Test
    fun tenantOfATokenWithoutTenantIsTheDefaultTenant() {
        val tokenPrincipal = SimpleTokenPrincipal("jti", SimplePrincipal("alice"))
        MembershipClaims.tenantOf(tokenPrincipal).assert().isEqualTo(TenantId.DEFAULT)
        MembershipClaims.tenantOf(SimpleTokenTenantPrincipal(tokenPrincipal, SimpleTenant("")))
            .assert().isEqualTo(TenantId.DEFAULT)
        MembershipClaims.tenantOf(SimpleTokenTenantPrincipal(tokenPrincipal, SimpleTenant("t")))
            .assert().isEqualTo(TenantId("t"))
    }

    /** Mapping a blank tenant to the default one would move the principal into another tenant. */
    @Test
    fun tenantOfABlankTenantIsKeptAndRefusedByTheScope() {
        val tokenPrincipal = SimpleTokenPrincipal("jti", SimplePrincipal("alice"))
        val tenant = MembershipClaims.tenantOf(SimpleTokenTenantPrincipal(tokenPrincipal, SimpleTenant(" ")))
        tenant.assert().isEqualTo(TenantId(" "))
        assertThrows<IllegalArgumentException> {
            ScopeSubject(tenant, true, MembershipClaims.parse(emptyList(), emptyList()))
        }
    }

    @Test
    fun markerOfLooksThroughTokenPrincipals() {
        val marked = MembershipMarkedPrincipal(SimplePrincipal("alice"), MembershipClaims.PROVIDER)
        val tokenPrincipal = SimpleTokenTenantPrincipal(SimpleTokenPrincipal("jti", marked), SimpleTenant("t"))

        MembershipClaims.markerOf(tokenPrincipal).assert().isEqualTo(MembershipClaims.PROVIDER)
        MembershipClaims.markerOf(marked).assert().isEqualTo(MembershipClaims.PROVIDER)
        tokenPrincipal.id.assert().isEqualTo("alice")
    }

    @Test
    fun markerOfUnmarkedPrincipal() {
        val tokenPrincipal =
            SimpleTokenTenantPrincipal(SimpleTokenPrincipal("jti", SimplePrincipal("alice")), SimpleTenant("t"))

        MembershipClaims.markerOf(tokenPrincipal).assert().isNull()
        MembershipClaims.markerOf(SimplePrincipal.ANONYMOUS).assert().isNull()
    }

    companion object {
        private const val SEED = 20261026
        private const val ITERATIONS = 2_000
    }
}

/**
 * The oracle of MEM-3: 7.x's `RoleScope` as it was when the 7.x path was removed, with its id aliases spelled
 * `String`. Which of a principal's roles apply in a space.
 *
 * A role written `role@space` applies only in `space`; a role without a suffix applies only in the default space.
 * The request names the space (`CoSec-Space-Id`), which the client controls, so it may only choose among the spaces
 * the principal's credential states: changing the header never picks up another space's grants. A malformed role
 * (empty role or space, more than one `@`) applies nowhere.
 */
private object RoleScope {
    private const val SEPARATOR = SpacedRoleId.SPACE_ID_SEPARATOR[0]

    /**
     * The bare role ids (without the `@space` suffix) of [roles] that apply in [spaceId].
     */
    fun rolesIn(spaceId: String, roles: Set<String>): Set<String> {
        if (roles.isEmpty()) {
            return roles
        }
        // The common case (default space, no spaced roles) keeps the principal's set without allocating.
        if (spaceId == SpaceIdCapable.DEFAULT && roles.none { it.indexOf(SEPARATOR) >= 0 || it.isEmpty() }) {
            return roles
        }
        val scoped = LinkedHashSet<String>(roles.size)
        for (role in roles) {
            roleIdIn(spaceId, role)?.let(scoped::add)
        }
        return scoped
    }

    /**
     * The bare role id of [role] when it applies in [spaceId], otherwise `null`.
     */
    fun roleIdIn(spaceId: String, role: String): String? {
        val separator = role.indexOf(SEPARATOR)
        if (separator < 0) {
            return role.takeIf { spaceId == SpaceIdCapable.DEFAULT && it.isNotEmpty() }
        }
        if (separator == 0 || role.indexOf(SEPARATOR, separator + 1) >= 0) {
            return null
        }
        val spaceLength = role.length - separator - 1
        if (spaceLength == 0 || spaceLength != spaceId.length || !role.regionMatches(separator + 1, spaceId, 0, spaceLength)) {
            return null
        }
        return role.substring(0, separator)
    }

    /**
     * Parses [role] (`role` or `role@space`) into its role and space, or `null` when it is malformed and so applies
     * nowhere. Tools that take role grants as text use the same syntax, so a grant keyed `role@space` matches exactly
     * the principals [rolesIn] gives that role in that space.
     */
    fun parse(role: String): SpacedRoleId? {
        val separator = role.indexOf(SEPARATOR)
        if (separator < 0) {
            return role.takeIf { it.isNotEmpty() }?.let { SpacedRoleId(it) }
        }
        val spaceId = role.substring(separator + 1)
        return roleIdIn(spaceId, role)?.let { SpacedRoleId(it, spaceId) }
    }
}
