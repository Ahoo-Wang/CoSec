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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.MembershipResolver
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.InMemoryMembershipProvider
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** RFC 0009 §4.4: converted keys keep exactly the access they had, and never gain any. */
class ApiKeyMigrationTest {
    private val acme = TenantId("acme")
    private val attachable = setOf("p-orders", "p-ops")

    /** The engine's view of attachments: only an attachable policy ever applied. */
    private val attachability = Attachability { _, policy ->
        when (policy.value) {
            in attachable -> Attachability.Attachable.YES
            "p-shared" -> Attachability.Attachable.AMBIGUOUS
            else -> Attachability.Attachable.NO
        }
    }

    private fun key(
        id: String,
        principal: String,
        tenant: String?,
        roles: List<String> = emptyList(),
        policies: List<String> = emptyList(),
        attributes: Map<String, String> = emptyMap(),
    ) = LegacyApiKey("h-$id", id, principal, tenant, roles, policies, attributes, null, true)

    /** Several keys of one principal with different roles and attachments, one without a tenant, one with nothing. */
    private val mixed = listOf(
        key("k1", "alice", "acme", roles = listOf("reader"), policies = listOf("p-orders")),
        key("k2", "alice", "acme", roles = listOf("writer@team-a", "reader@team-a"), policies = listOf("p-orders")),
        key("k3", "alice", "acme", policies = listOf("p-missing", "p-shared")),
        key("k4", "svc", null, roles = listOf("ops"), policies = listOf("p-ops")),
        key("k5", "bob", "acme"),
    )

    /** What a key could do in [space]: its roles there, and the policies it reached by attachment. */
    private data class Access(val roles: Set<RoleId>, val policies: Set<PolicyId>)

    private fun before(key: LegacyApiKey, space: SpaceId): Access {
        val roles = MembershipClaims.parse(key.roles, emptyList()).roles[space].orEmpty()
        // A space other than the default resolves only for a principal with a role in it (ScopeResolver).
        if (space != SpaceId.DEFAULT && roles.isEmpty()) return Access(emptySet(), emptySet())
        val attached = key.policies.filter { it in attachable }.mapTo(HashSet(), ::PolicyId)
        return Access(roles, attached)
    }

    private fun after(plan: ApiKeyMigrationPlan, provider: InMemoryMembershipProvider, id: String, space: SpaceId): Access {
        val converted = plan.keys.single { it.key.id == id }.key
        val memberships = runBlocking {
            MembershipResolver(provider).resolve(
                TenantId(converted.tenantId),
                PrincipalId(converted.principalId),
                MembershipSource.Provider.capped(converted.roles!!),
            )
        }!!
        val held = memberships.roles[space].orEmpty()
        if (space != SpaceId.DEFAULT && held.isEmpty()) return Access(emptySet(), emptySet())
        memberships.attachments.assert().isEmpty()
        val bound = plan.bindings.filter { it.tenant.value == converted.tenantId && it.role in held }.mapTo(
            HashSet()
        ) { it.policy }
        return Access(held.filterTo(HashSet()) { !it.value.startsWith(ApiKeyMigration.DEFAULT_ROLE_PREFIX) }, bound)
    }

    /** The provider after the operator granted the report's memberships, beside what the principals already held. */
    private fun provider(plan: ApiKeyMigrationPlan): InMemoryMembershipProvider {
        val held = mapOf(
            (acme to "alice") to listOf("admin", "admin@team-a"),
            (TenantId.DEFAULT to "svc") to listOf("root-ish")
        )
        val owners = plan.memberships.map { it.tenant to it.principal } + held.keys
        return InMemoryMembershipProvider().apply {
            owners.distinct().forEach { owner ->
                val roles = plan.memberships.filter { (it.tenant to it.principal) == owner }.flatMap { it.roles } +
                    held[owner].orEmpty()
                val parsed = MembershipClaims.parse(roles, listOf("p-direct"))
                put(
                    owner.first,
                    PrincipalId(owner.second),
                    Memberships(Memberships.Source.PROVIDER, parsed.roles, parsed.attachments)
                )
            }
        }
    }

    @Test
    fun `every key keeps exactly the access it had, in every space`() {
        val plan = ApiKeyMigration(attachability = attachability).plan(mixed)
        plan.accepted.assert().isTrue()
        val provider = provider(plan)
        val spaces = listOf(SpaceId.DEFAULT, SpaceId("team-a"), SpaceId("team-b"))
        mixed.forEach { key ->
            spaces.forEach { space ->
                after(
                    plan,
                    provider,
                    key.id,
                    space
                ).assert().describedAs("${key.id} in $space").isEqualTo(before(key, space))
            }
        }
    }

    @Test
    fun `a key's cap is its own roles and its own role, never another key's`() {
        val plan = ApiKeyMigration(attachability = attachability).plan(mixed)
        fun cap(id: String) = plan.keys.single { it.key.id == id }.key.roles
        cap("k1").assert().containsExactly("reader", "api-key.k1")
        cap("k2").assert().containsExactly("writer@team-a", "reader@team-a", "api-key.k2", "api-key.k2@team-a")
        // Attachments that never applied give no role, so the key keeps no roles at all: never uncapped.
        cap("k3").assert().isEmpty()
        cap("k5").assert().isEmpty()
        plan.keys.single { it.key.id == "k3" }.dropped.assert().hasSize(2)
        // The principal holds the union of its keys' caps.
        plan.memberships.single { it.principal == "alice" }.roles.assert()
            .containsExactlyInAnyOrder(
                "reader",
                "api-key.k1",
                "writer@team-a",
                "reader@team-a",
                "api-key.k2",
                "api-key.k2@team-a"
            )
        plan.bindings.map { it.id }.assert().containsExactly(
            "api-key.k1:p-orders",
            "api-key.k2:p-orders",
            "api-key.k4:p-ops"
        )
    }

    @Test
    fun `a key without a tenant goes to the default tenant, flagged`() {
        val k4 = ApiKeyMigration(attachability = attachability).plan(mixed).keys.single { it.key.id == "k4" }
        k4.key.tenantId.assert().isEqualTo(TenantId.DEFAULT.value)
        k4.defaultTenant.assert().isTrue()
    }

    @Test
    fun `a key with attributes or malformed roles is refused`() {
        val plan = ApiKeyMigration(attachability = attachability).plan(
            mixed + key("k6", "eve", "acme", attributes = mapOf("team" to "a")) + key("k7", "eve", "acme", roles = listOf("a@b@c")),
        )
        plan.accepted.assert().isFalse()
        plan.rejections.assert().hasSize(2)
        plan.keys.map { it.key.id }.assert().doesNotContain("k6", "k7")
    }

    @Test
    fun `the report says what to grant and to bind`() {
        val report = ApiKeyMigration(
            rolePrefix = "pat-",
            attachability = attachability
        ).plan(mixed).report(true, listOf("h-old"))
        report.get("accepted").booleanValue().assert().isTrue()
        report.get("dryRun").booleanValue().assert().isTrue()
        report.get("alreadyConverted").single().asString().assert().isEqualTo("h-old")
        report.get("roleBindings").first().get("subject").get("role").asString().assert().isEqualTo("pat-k1")
        report.get(
            "keys"
        ).single { it.get("id").asString() == "k4" }.get("defaultTenant").booleanValue().assert().isTrue()
    }

    @Test
    fun `stored keys before RFC 0009 are read strictly`() {
        val parsed = LegacyApiKey.parse(
            "h",
            """{"id":"k1","principalId":"alice","tenantId":null,"roles":["r"],"policies":["p"],"attributes":{"a":"b"},"expiresAt":"2026-11-01T00:00:00Z","enabled":false}""",
        )
        parsed.tenantId.assert().isNull()
        parsed.attributes.assert().containsEntry("a", "b")
        parsed.enabled.assert().isFalse()
        LegacyApiKey.isConverted("""{"format":2}""").assert().isTrue()
        LegacyApiKey.isConverted("""{"id":"k1"}""").assert().isFalse()
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            LegacyApiKey.parse(
                "h",
                """{"id":"k","principalId":"a","extra":1}"""
            )
        }
    }
}
