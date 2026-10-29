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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.MembershipResolver
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.InMemoryMembershipProvider
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * RFC 0009 §4.4, reviews H2 and M1: converted keys keep exactly what they could do, minus what their principal was
 * bound to by name (reported), and no principal gains anything unless asked to. Decided by the engine itself, before
 * and after, so a DENY of an attached policy and an attachment that never resolved (indeterminate) count.
 */
class ApiKeyMigrationTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val acme = TenantId("acme")

    private fun policy(id: String, attachable: Boolean, vararg statements: String) =
        """{"id":"$id","attachable":$attachable,"statements":[${statements.joinToString(",")}]}"""

    private fun allow(action: String) = """{"effect":"allow","action":"$action"}"""

    private fun deny(action: String) = """{"effect":"deny","action":"$action"}"""

    private val platformPolicies = listOf(
        policy("shared", true, allow("audit:read")),
        policy("differs", true, allow("audit:read")),
        policy("no-delete", false, deny("order:delete")),
    )
    private val tenantPolicies = listOf(
        policy("read-orders", false, allow("order:read"), allow("order:delete")),
        policy("write-orders", false, allow("order:write")),
        policy("reports", true, allow("report:read"), deny("report:delete"), allow("report:delete")),
        policy("shared", true, allow("audit:read")),
        policy("differs", true, allow("audit:read"), allow("audit:write")),
        policy("plain", false, allow("audit:read")),
        policy("admin", false, allow("admin:do"), allow("report:delete")),
    )
    private val platformBindings = listOf(
        """{"id":"g:no-delete","subject":"*","app":"*","space":"*","policy":"no-delete"}""",
    )
    private val tenantBindings = listOf(
        """{"id":"reader","subject":{"role":"reader"},"app":"*","space":"*","policy":"read-orders"}""",
        """{"id":"writer","subject":{"role":"writer"},"app":"*","space":"team-a","policy":"write-orders"}""",
        """{"id":"p:alice:admin","subject":{"principal":"alice","tenant":"acme"},"app":"*","space":"*","policy":"admin"}""",
        """{"id":"p:carol:admin","subject":{"principal":"carol","tenant":"acme"},"app":"*","space":"*","policy":"admin"}""",
        // A principal someone already made, named like the one a key "taken" would move to.
        """{"id":"p:taken","subject":{"principal":"apikey:taken","tenant":"acme"},"app":"*","space":"*","policy":"admin"}""",
    )

    private fun document(kind: DocumentKind, owner: String, policies: List<String>, bindings: List<String>): PolicyDocument =
        PolicyDocumentCodec.decode(
            kind,
            owner,
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-10-01T00:00:00Z",""" +
                    """"policies":[${policies.joinToString(",")}],"bindings":[${bindings.joinToString(",")}]}"""
                ).encodeToByteArray(),
        )

    private val platform = document(DocumentKind.PLATFORM, "", platformPolicies, platformBindings)

    private fun tenant(extra: List<String> = emptyList()) = document(
        DocumentKind.TENANT,
        "acme",
        tenantPolicies,
        tenantBindings + extra
    )

    /**
     * What a 7.x key decided against: acme's document without the bindings that name a principal, which no 7.x key
     * reached. The key's own attachments grant there, which a capped credential's no longer do (RC1-1), so the key is
     * decided as an uncapped one over these documents.
     */
    private val legacyTenant = document(
        DocumentKind.TENANT,
        "acme",
        tenantPolicies,
        tenantBindings.filterNot { it.contains("\"principal\"") },
    )

    /** The published documents as the plan reads them: [platform] and acme's tenant document. */
    private val published = object : PublishedDocuments {
        private val documents = PublishedDocumentsFixture(platform, mapOf(acme to tenant()))

        override fun attachable(tenant: TenantId, policy: PolicyId) = documents.attachable(tenant, policy)

        override fun principalGrants(tenant: TenantId, principal: String) = documents.principalGrants(tenant, principal)

        override fun boundRole(role: RoleId) = documents.boundRole(role)
    }

    private fun key(
        id: String,
        principal: String,
        tenant: String? = "acme",
        roles: List<String> = emptyList(),
        policies: List<String> = emptyList(),
        attributes: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
    ) = LegacyApiKey("h-$id", id, principal, tenant, roles, policies, attributes, null, enabled)

    /** What each principal holds today, from the application's membership source. */
    private val export: Map<Pair<TenantId, String>, Set<String>> = mapOf(
        (acme to "alice") to setOf("reader", "writer@team-a"),
        (acme to "carol") to setOf("auditor"),
        (acme to "apikey:known") to setOf("reader"),
    )

    /** Keys of one principal with different roles and attachments, one without a tenant, one that does not work. */
    private val mixed = listOf(
        key("k1", "alice", roles = listOf("reader")),
        key("k2", "alice", roles = listOf("reader"), policies = listOf("reports")),
        key("k3", "alice", roles = listOf("writer@team-a")),
        key("k4", "bob", roles = listOf("reader")),
        key("k5", "carol", roles = listOf("reader"), policies = listOf("shared", "reports")),
        key("k6", "carol", roles = listOf("writer@team-a"), enabled = false),
        key("k7", "frank", tenant = null, roles = listOf("reader")),
        key("k8", "alice"),
    )

    private fun plan(keys: List<LegacyApiKey> = mixed, grantTo: Set<String> = setOf("carol")) =
        ApiKeyMigration(published, export, grantTo, now = now).plan(keys)

    private val actions = listOf(
        "order:read",
        "order:write",
        "order:delete",
        "report:read",
        "report:delete",
        "audit:read",
        "audit:write",
        "admin:do"
    ).map { Action(it.substringBefore(':'), it.substringAfter(':')) }
    private val spaces = listOf("", "team-a", "team-b")

    /** The engine's decision kind, or `scope-rejected` when the space does not resolve for these memberships. */
    private fun decide(
        tenant: TenantId,
        principal: String,
        memberships: Memberships,
        principalBindings: Boolean,
        documents: PolicyDocument,
        action: Action,
        space: String,
    ): String {
        val resolution = ScopeResolver.resolve(
            ScopeSubject(tenant, true, memberships),
            DeclaredScope("", space),
            HostScope(null)
        )
        val scope = (resolution as? ScopeResolution.Resolved)?.scope ?: return "scope-rejected"
        val subject =
            Subject(principal, tenant.value, true, emptySet(), emptySet(), emptyMap(), false, principalBindings, false)
        val request = AuthzRequest(
            subject,
            Scope.declared(scope.tenant.value, scope.app.value, scope.space.value),
            action,
            Resource("x", "1", emptyMap()),
            Environment("10.0.0.1", "r", "", false),
        )
        val compiler = PolicyDocumentCompiler()
        val view = SnapshotView(
            compiler.compile(platform),
            null,
            if (tenant == acme) {
                compiler.compile(
                    documents
                )
            } else {
                compiler.compile(document(DocumentKind.TENANT, tenant.value, emptyList(), emptyList()))
            },
        )
        val decision = CoSecFlatEngine.evaluate(
            FlatRequest(request, scope, memberships, now.toEpochMilli()),
            view
        ).decision
        return decision::class.simpleName!!
    }

    private fun claims(roles: Collection<String>, policies: Collection<String> = emptyList()) = MembershipClaims.parse(
        roles,
        policies
    )

    /** The provider after the operator granted the report's memberships, beside what the export says each holds. */
    private fun provider(plan: ApiKeyMigrationPlan): InMemoryMembershipProvider {
        val roles = HashMap<Pair<TenantId, String>, MutableSet<String>>()
        export.forEach { (owner, held) -> roles.getOrPut(owner) { LinkedHashSet() } += held }
        plan.memberships.forEach { roles.getOrPut(it.tenant to it.principal) { LinkedHashSet() } += it.roles }
        return InMemoryMembershipProvider().apply {
            roles.forEach { (owner, held) ->
                val parsed = claims(held)
                put(
                    owner.first,
                    PrincipalId(owner.second),
                    Memberships(Memberships.Source.PROVIDER, parsed.roles, emptyList())
                )
            }
        }
    }

    private fun after(plan: ApiKeyMigrationPlan): PolicyDocument = tenant(
        plan.principalBindings.map {
            """{"id":"${it.id}","subject":{"principal":"${it.principal}","tenant":"${it.tenant.value}"},"app":"*","space":"*","policy":"${it.policy.value}"}"""
        } + plan.roleBindings.map {
            """{"id":"${it.id}","subject":{"role":"${it.role.value}"},"app":"*","space":"*","policy":"${it.policy.value}"}"""
        },
    )

    /** Where a converted key decides otherwise than before, less its principal's bindings by name: none, or a defect. */
    private fun differences(plan: ApiKeyMigrationPlan, documents: PolicyDocument = after(plan)): List<String> {
        val provider = provider(plan)
        val found = ArrayList<String>()
        plan.keys.filter { it.legacy.usableAt(now) }.forEach { converted ->
            val legacy = converted.legacy
            val key = converted.key
            val resolved = runBlocking {
                MembershipResolver(provider).resolve(
                    TenantId(key.tenantId),
                    PrincipalId(key.principalId),
                    key.roles?.let(MembershipSource.Provider::capped) ?: MembershipSource.Provider.UNCAPPED,
                )
            }
            actions.forEach { action ->
                spaces.forEach { space ->
                    val before = decide(
                        legacy.tenant,
                        legacy.principalId,
                        claims(legacy.roles, legacy.policies),
                        true,
                        legacyTenant,
                        action,
                        space
                    )
                    val after = resolved?.let {
                        decide(TenantId(key.tenantId), key.principalId, it, key.roles == null, documents, action, space)
                    } ?: "refused"
                    if (after != before) found += "${key.id} ${action.verb} in [$space]: $before, then $after"
                }
            }
        }
        return found
    }

    /**
     * Every key decides after as it did before, without its principal's own bindings: the losses the report lists. Never
     * a request permitted after that was not before, and never one refused that was permitted.
     */
    @Test
    fun `every key keeps exactly what it could do, less its principal's bindings by name`() {
        val plan = plan()
        plan.accepted.assert().describedAs(plan.rejections.toString()).isTrue()
        differences(plan).assert().isEmpty()
    }

    /** The oracle sees what matters: permits, a DENY of an attached policy, and a conversion that is wrong. */
    @Test
    fun `the oracle tells a wrong conversion from a right one`() {
        val k2 = mixed[1]
        fun before(action: String) = decide(
            acme,
            "alice",
            claims(k2.roles, k2.policies),
            true,
            legacyTenant,
            Action("report", action),
            "",
        )
        before("read").assert().isEqualTo("Permit")
        before("delete").assert().isEqualTo("Deny")
        decide(acme, "alice", claims(k2.roles, listOf("ghost")), true, legacyTenant, Action("order", "read"), "")
            .assert().isEqualTo("Indeterminate")
        val plan = plan()
        // Without the bindings that carry the attachments, k2 loses its reports and k5 its audit.
        differences(plan, tenant()).assert().isNotEmpty()
        // Uncapped in place, k1 would reach alice's own binding, and with it the admin pages.
        val uncapped = ApiKeyMigrationPlan(
            plan.keys.map {
                if (it.key.id != "k1") {
                    it
                } else {
                    ConvertedApiKey(
                        it.legacy,
                        me.ahoo.cosec.spi.apikey.ApiKey(
                            it.key.id,
                            it.key.principalId,
                            it.key.tenantId,
                            null,
                            null,
                            true
                        ),
                        it.mode,
                        it.lost,
                    )
                }
            },
            plan.memberships,
            plan.principalBindings,
            plan.roleBindings,
            plan.rejections,
        )
        differences(uncapped).filter { it.startsWith("k1 ") }.assert().isNotEmpty()
    }

    /** Review M1: a principal gains nothing from its keys unless asked to; carol was, and the report says what. */
    @Test
    fun `no principal's own access changes unless the union was opted into`() {
        val plan = plan()
        val provider = provider(plan)
        val documents = after(plan)
        listOf("alice", "bob").forEach { principal ->
            val before = claims(
                export[acme to principal].orEmpty()
            ).let { Memberships(Memberships.Source.PROVIDER, it.roles, emptyList()) }
            val after = runBlocking {
                MembershipResolver(
                    provider
                ).resolve(acme, PrincipalId(principal), MembershipSource.Provider.UNCAPPED)
            }
            actions.forEach { action ->
                spaces.forEach { space ->
                    val was = decide(acme, principal, before, true, tenant(), action, space)
                    val decided = after?.let { decide(acme, principal, it, true, documents, action, space) } ?: "unknown"
                    decided.assert().describedAs(
                        "$principal ${action.verb} in [$space]"
                    ).isEqualTo(if (principal == "bob") "unknown" else was)
                }
            }
        }
        val carol = plan.memberships.single { it.principal == "carol" }
        carol.newlyGained!!.assert().containsExactly("reader", "api-key.k5")
        // A key that did not work gives its principal nothing.
        carol.roles.assert().doesNotContain("writer@team-a")
    }

    @Test
    fun `each key goes the way it can without gaining`() {
        val plan = plan()
        fun of(id: String) = plan.keys.single { it.key.id == id }
        of("k1").mode.assert().isEqualTo(ConvertedApiKey.Mode.IN_PLACE)
        of("k1").key.roles.assert().containsExactly("reader")
        of("k1").lost.assert().containsExactly("tenant:acme/p:alice:admin")
        // Attachments, or roles the principal does not hold: a principal of its own, holding exactly the key's roles.
        of("k2").mode.assert().isEqualTo(ConvertedApiKey.Mode.OWN_PRINCIPAL)
        of("k2").key.principalId.assert().isEqualTo("apikey:k2")
        of("k2").key.roles.assert().isNull()
        of("k2").changes.assert().isEqualTo(ApiKeyMigration.PRINCIPAL_ID_CHANGES)
        plan.memberships.single { it.principal == "apikey:k2" }.roles.assert().containsExactly("reader")
        plan.principalBindings.map { it.id }.assert().contains("apikey:k2:reports")
        of("k4").mode.assert().isEqualTo(ConvertedApiKey.Mode.OWN_PRINCIPAL)
        of("k7").key.tenantId.assert().isEqualTo(TenantId.DEFAULT.value)
        of("k8").mode.assert().isEqualTo(ConvertedApiKey.Mode.IN_PLACE)
        of("k8").key.roles.assert().isEmpty()
        of("k5").mode.assert().isEqualTo(ConvertedApiKey.Mode.GRANTED)
        of("k5").key.roles.assert().containsExactly("reader", "api-key.k5")
        plan.roleBindings.map { it.id }.assert().containsExactly("api-key.k5:shared", "api-key.k5:reports")
        // Without an export nothing is shown to be held: every key with roles gets a principal of its own.
        ApiKeyMigration(published, null, now = now).plan(listOf(key("k1", "alice", roles = listOf("reader"))))
            .keys.single().mode.assert().isEqualTo(ConvertedApiKey.Mode.OWN_PRINCIPAL)
    }

    /** Review H2 and L1: what cannot be converted exactly is refused, and then nothing is written. */
    @Test
    fun `a key that cannot be converted exactly is refused`() {
        val refused = listOf(
            key("k-attr", "eve", attributes = mapOf("team" to "a")),
            key("k-bad", "eve", roles = listOf("a@b@c")),
            key("k-differs", "eve", policies = listOf("differs")),
            key("k-plain", "eve", policies = listOf("plain")),
            key("k-missing", "eve", policies = listOf("ghost")),
            key("k@at", "eve"),
            key("k*star", "eve"),
            key("k blank", "eve"),
            key("k-prefixed", "eve", roles = listOf("api-key.other")),
            // Review N2: one id, two principals (7.x never made ids unique), and principals that already exist.
            key("ci", "alice", roles = listOf("reader")),
            key("ci", "bob", roles = listOf("writer@team-a")),
            key("taken", "eve", roles = listOf("reader")),
            key("known", "eve", roles = listOf("reader")),
        )
        val plan = plan(mixed + refused)
        plan.accepted.assert().isFalse()
        plan.rejections.assert().hasSize(refused.size)
        plan.keys.map { it.key.id }.assert().doesNotContainAnyElementsOf(refused.map { it.id })
        // A per-key role must not take a role a document already binds.
        val taken = object : PublishedDocuments by published {
            override fun boundRole(role: RoleId) = role.value == "api-key.k5"
        }
        ApiKeyMigration(taken, export, setOf("carol"), now = now).plan(listOf(mixed[4])).rejections.assert().hasSize(1)
    }

    @Test
    fun `the report says what to grant, to bind and what changes`() {
        val report = plan().report(true, listOf("h-old"), listOf("h-changed"))
        report.get("accepted").booleanValue().assert().isTrue()
        report.get("alreadyConverted").single().asString().assert().isEqualTo("h-old")
        report.get("changedSinceRead").single().asString().assert().isEqualTo("h-changed")
        val k2 = report.get("keys").single { it.get("id").asString() == "k2" }
        k2.get("mode").asString().assert().isEqualTo("own-principal")
        k2.get("cap").isNull.assert().isTrue()
        report.get(
            "principalBindings"
        ).first().get("subject").get("principal").asString().assert().isEqualTo("apikey:k2")
        report.get("memberships").single { it.get("principal").asString() == "carol" }.get("newlyGained").size()
            .assert().isEqualTo(2)
    }

    @Test
    fun `stored keys before RFC 0009 and a memberships export are read strictly`() {
        val parsed = LegacyApiKey.parse(
            "h",
            """{"id":"k1","principalId":"alice","tenantId":null,"roles":["r"],"policies":["p"],"attributes":{"a":"b"},"expiresAt":"2026-11-01T00:00:00Z","enabled":false}""",
        )
        parsed.tenant.assert().isEqualTo(TenantId.DEFAULT)
        parsed.usableAt(now).assert().isFalse()
        LegacyApiKey.isConverted("""{"format":2}""").assert().isTrue()
        assertThrows<IllegalArgumentException> { LegacyApiKey.parse("h", """{"id":"k","principalId":"a","extra":1}""") }
        MembershipsExport.read("""[{"tenant":"acme","principal":"alice","roles":["reader"]}]""".encodeToByteArray())
            .assert().isEqualTo(mapOf((acme to "alice") to setOf("reader")))
        // Two entries for one principal: refused, never the last one kept.
        assertThrows<IllegalArgumentException> {
            MembershipsExport.read(
                """[{"tenant":"acme","principal":"alice","roles":["reader"]},{"tenant":"acme","principal":"alice","roles":["admin"]}]"""
                    .encodeToByteArray(),
            )
        }
        assertThrows<IllegalArgumentException> { MembershipsExport.read("""{"tenant":"acme"}""".encodeToByteArray()) }
    }
}

/** [PublishedDocuments] over documents in memory, through the same reading as [StoredDocuments]. */
class PublishedDocumentsFixture(platform: PolicyDocument, tenants: Map<TenantId, PolicyDocument>) : PublishedDocuments {
    private val store = me.ahoo.cosec.control.store.InMemoryControlStore().also { store ->
        DocumentPublisher(store).publish(
            mapOf(me.ahoo.cosec.spi.snapshot.SnapshotKey.Platform to platform) +
                tenants.map { (tenant, document) -> me.ahoo.cosec.spi.snapshot.SnapshotKey.Tenant(tenant.value) to document },
        )
    }
    private val documents = StoredDocuments(store)

    override fun attachable(tenant: TenantId, policy: PolicyId) = documents.attachable(tenant, policy)

    override fun principalGrants(tenant: TenantId, principal: String) = documents.principalGrants(tenant, principal)

    override fun boundRole(role: RoleId) = documents.boundRole(role)
}
