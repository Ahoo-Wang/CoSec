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

import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.migrate.Fixtures7x.app
import me.ahoo.cosec.migrate.Fixtures7x.appPermission
import me.ahoo.cosec.migrate.Fixtures7x.permission
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.migrate.Fixtures7x.statement
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

class ConverterTest {
    private val converter = Converter()
    private val at = Instant.parse("2026-10-26T00:00:00Z")

    private fun convert(source: Source7x, partition: Partition = Partition.PLATFORM, root: RootOptions? = null) =
        converter.convert(source, ConvertOptions(partition, root, at))

    /** Through the stored form, as publish and the snapshot source read it, and compiled again. */
    private fun PolicyDocument.roundTrip(): PolicyDocument {
        val decoded = PolicyDocumentCodec.decode(kind, owner, PolicyDocumentCodec.encode(this))
        PolicyDocumentCompiler().compile(decoded)
        return decoded
    }

    private val consistent = source(
        policies = listOf(
            "g" to policy(
                "g",
                "global",
                listOf(
                    statement("""{"path":{"pattern":"/orders/**","method":["get","HEAD"]}}""", name = "read"),
                    statement("\"/admin/**\"", "deny", name = "no-admin"),
                ),
            ),
            "c" to policy("c", "custom", listOf(statement("""["/reports/**","/exports/**"]"""))),
            "s" to policy("s", "system", listOf(statement("\"*\""))),
        ),
        globalIndex = setOf("g"),
        apps = listOf(
            app(
                "order",
                appPermission(
                    "order",
                    permission("read", """{"path":{"pattern":"/orders/{id}","method":"GET"}}"""),
                    permission("write", """{"path":{"pattern":"/orders/**","method":["POST","PUT"]}}"""),
                    permission("internal", "\"/orders/internal/**\"", "deny"),
                ),
                "admin" to """["*"]""",
                "clerk" to """["read","write"]""",
                "auditor@team" to """["read"]""",
            ),
        ),
    )

    @Test
    fun `global, attachable and permission policies become platform and app documents that compile`() {
        val conversion = convert(consistent)
        conversion.report.rejections.assert().isEmpty()
        conversion.documents.keys.assert().containsExactly(SnapshotKey.Platform, SnapshotKey.App("order"))

        val platform = conversion.documents.getValue(SnapshotKey.Platform).roundTrip()
        platform.revision.assert().isEqualTo(1L)
        platform.publishedAt.assert().isEqualTo(at)
        platform.policies.map { it.id.value to it.attachable }.assert()
            .containsExactly("g" to false, "c" to true, "s" to true)
        val global = platform.policies.first()
        global.statements.map { it.effect }.assert().containsExactly(Effect.ALLOW, Effect.DENY)
        global.statements[0].actions.assert().containsExactly("http:GET", "http:HEAD")
        global.statements[0].resources.single().pattern.assert().isEqualTo("http:path:/orders/**")
        global.statements[1].actions.assert().containsExactly("http:*")
        platform.policies[1].statements.single().resources.map { it.pattern }.assert()
            .containsExactly("http:path:/reports/**", "http:path:/exports/**")
        platform.policies[2].statements.single().actions.assert().containsExactly("*")
        val binding = platform.bindings.single()
        binding.id.value.assert().isEqualTo("g:g")
        binding.subject.assert().isEqualTo(SubjectRef.Anyone)
        binding.scope.app.assert().isEqualTo(Match.Any)
        binding.scope.space.assert().isEqualTo(Match.Any)

        val order = conversion.documents.getValue(SnapshotKey.App("order")).roundTrip()
        order.kind.assert().isEqualTo(DocumentKind.APP)
        // Only granted permissions become policies; `*` holds every permission in catalog order.
        order.policies.map { it.id.value }.assert().containsExactly(
            "perm:order/read",
            "perm:order/write",
            "perm:order/*"
        )
        order.policies.last().statements.map { it.name }.assert().containsExactly("read", "write", "internal")
        order.policies.last().statements.last().effect.assert().isEqualTo(Effect.DENY)
        order.bindings.map { it.id.value to it.policy.value }.assert().containsExactly(
            "r:admin:0" to "perm:order/*",
            "r:clerk:0" to "perm:order/read",
            "r:clerk:1" to "perm:order/write",
            "r:auditor@team:0" to "perm:order/read",
        )
        order.bindings.forEach { it.scope.app.assert().isEqualTo(Match.Exactly(AppId("order"))) }
        order.bindings.last().scope.space.assert().isEqualTo(Match.Exactly(SpaceId("team")))
        order.bindings.first().scope.space.assert().isEqualTo(Match.Exactly(SpaceId.DEFAULT))
        (order.bindings.first().subject as SubjectRef.Role).tenant.assert().isNull()
        val catalog = order.catalog!!.groups.single()
        catalog.description.assert().isEqualTo("the group")
        catalog.permissions.map { it.id }.assert().containsExactly("read", "write", "internal")

        conversion.report.counts["globalPolicies"].assert().isEqualTo(1)
        conversion.report.counts["attachablePolicies"].assert().isEqualTo(2)
        conversion.report.counts["grants"].assert().isEqualTo(3)
    }

    @Test
    fun `provenance maps every policy statement and binding back to 7x`() {
        val provenance = convert(consistent).provenance
        provenance.policies.first { it.policyId == "g" }.statements.assert()
            .containsExactly("policy:g#$.statements[0]", "policy:g#$.statements[1]")
        provenance.policies.first { it.policyId == "perm:order/*" }.statements.assert().hasSize(3)
        provenance.bindings.map { it.bindingId }.assert()
            .containsExactlyInAnyOrder("g:g", "r:admin:0", "r:clerk:0", "r:clerk:1", "r:auditor@team:0")
        provenance.bindings.first { it.bindingId == "r:auditor@team:0" }.source.assert()
            .contains("role [auditor]").contains("space [team]").contains("permission [read]")
    }

    @Test
    fun `the report lists the semantic changes the data is exposed to`() {
        val changes = convert(consistent).report.semanticChanges
        changes.filter { it.change == "C1b" }.map { it.location }.assert().containsExactlyInAnyOrder(
            "policy:g#$.statements[1]",
            "app:permission:order#$.groups[0].permissions[2]",
        )
        changes.single { it.change == "C3" }.location.assert().isEqualTo("app:role:permission:order:auditor@team")
    }

    @Test
    fun `every rejection kind is reported with its location and nothing is converted`() {
        val source = source(
            policies = listOf(
                "broken" to "{",
                "perm:x" to policy("perm:x"),
                "unindexed" to policy("unindexed", "global"),
                "twice" to policy("twice"),
                "twice" to policy("twice"),
                "orphan" to policy("orphan", tenantId = ""),
                "conditions" to policy(
                    "conditions",
                    statements = listOf(
                        statement(condition = """{"inRole":{"value":"admin"}}"""),
                        statement(condition = """{"spel":{"value":"true"}}"""),
                        statement(condition = """{"noSuchMatcher":{}}"""),
                    ),
                ),
                "actions" to policy(
                    "actions",
                    statements = listOf(
                        statement("\"/users/#{principal.id}/**\""),
                        statement("""{"noSuchAction":{}}"""),
                    ),
                ),
                "pattern" to policy("pattern", statements = listOf(statement("\"/a/{x:[0-9]+}\""))),
            ),
            globalIndex = setOf("ghost"),
            apps = listOf(
                app("*", appPermission("*", permission("p"))),
                app("order", appPermission("order", permission("p")), "clerk" to """["p","nope"]"""),
            ),
            marker = "6",
        )
        val report = convert(source, Partition.BY_OWNER, RootOptions(expiresAt = null)).report
        report.rejections.map { it.kind }.toSet().assert().isEqualTo(RejectionKind.entries.toSet())
        report.rejections.forEach { it.location.assert().isNotBlank() }
        report.accepted.assert().isFalse()
        convert(source, Partition.BY_OWNER, RootOptions(expiresAt = null)).documents.assert().isEmpty()

        fun located(kind: RejectionKind) = report.rejections.filter { it.kind == kind }.map { it.location }
        located(RejectionKind.UNDECODABLE).assert().contains("policy:broken")
        located(RejectionKind.RESERVED_POLICY_ID).assert().containsExactly("policy:perm:x")
        located(RejectionKind.GLOBAL_TYPE_NOT_INDEXED).assert().containsExactly("policy:unindexed")
        located(RejectionKind.DUPLICATE_ID).assert().containsExactly("policy:twice")
        located(RejectionKind.OWNER_TENANT).assert().containsExactly("policy:orphan")
        located(RejectionKind.GLOBAL_INDEX_WITHOUT_CONTENT).assert().containsExactly("global")
        located(RejectionKind.INVALID_ID).assert().containsExactly("app:*")
        located(RejectionKind.UNDEFINED_PERMISSION).assert().containsExactly("app:role:permission:order:clerk")
        located(RejectionKind.SCHEMA_MARKER).assert().containsExactly("meta:schema")
        located(RejectionKind.CONDITION_NOT_TRANSLATED).assert()
            .containsExactly("policy:conditions#$.statements[0].condition.inRole")
        located(RejectionKind.EXPRESSION_CONDITION).assert()
            .containsExactly("policy:conditions#$.statements[1].condition.spel")
        located(RejectionKind.CUSTOM_CONDITION).assert()
            .containsExactly("policy:conditions#$.statements[2].condition.noSuchMatcher")
        located(RejectionKind.ACTION_NOT_TRANSLATED).assert().containsExactly("policy:actions#$.statements[0].action")
        located(RejectionKind.CUSTOM_ACTION).assert().containsExactly("policy:actions#$.statements[1].action")
        located(RejectionKind.DOES_NOT_COMPILE).assert().containsExactly("policy:pattern#$.statements[0]")
    }

    @Test
    fun `a 7x path action decodes with spring-web from the runtime classpath and converts`() {
        // cosec-migrate's own runtime dependency, not a test addition.
        Class.forName("org.springframework.web.util.pattern.PathPatternParser")
        val conversion =
            convert(
                source(
                    listOf(
                        "p" to policy("p", statements = listOf(statement("""{"path":{"pattern":"/a/{id}","method":"GET"}}""")))
                    )
                )
            )
        conversion.report.rejections.assert().isEmpty()
        conversion.documents.getValue(
            SnapshotKey.Platform
        ).policies.single().statements.single().resources.single().pattern
            .assert().isEqualTo("http:path:/a/{id}")
    }

    @Test
    fun `a pattern outside the HttpPathPattern subset is rejected at its statement`() {
        val report = convert(
            source(listOf("p" to policy("p", statements = listOf(statement(), statement("\"/a/{x:[0-9]+}\"")))))
        ).report
        val rejection = report.rejections.single()
        rejection.kind.assert().isEqualTo(RejectionKind.DOES_NOT_COMPILE)
        rejection.location.assert().isEqualTo("policy:p#$.statements[1]")
    }

    @Test
    fun `an enabled root becomes an expiring break-glass binding bound by global denies`() {
        val expiresAt = Instant.parse("2026-12-31T00:00:00Z")
        val conversion = convert(consistent, root = RootOptions("cosec", expiresAt))
        conversion.report.rejections.assert().isEmpty()
        val root = conversion.documents.getValue(SnapshotKey.Platform).roundTrip().bindings.single { it.breakGlass }
        root.id.value.assert().isEqualTo(Converter.ROOT_BINDING_ID)
        root.policy.assert().isEqualTo(BuiltinPolicies.ROOT)
        root.subject.assert().isEqualTo(
            SubjectRef.Principal(TenantId.PLATFORM, me.ahoo.cosec.model.PrincipalId("cosec"))
        )
        root.expiresAt.assert().isEqualTo(expiresAt.toEpochMilli())
        val changes = conversion.report.semanticChanges
        changes.single { it.change == "C1d" }.location.assert().isEqualTo("--root-expires-at")
        changes.single { it.change == "C2" }.location.assert().isEqualTo("policy:g#$.statements[1]")
    }

    @Test
    fun `by owner puts attachable policies into their tenant's document`() {
        val conversion = convert(
            source(
                listOf(
                    "g" to policy("g", "global"),
                    "c" to policy("c", tenantId = "acme"),
                    "p" to policy("p", tenantId = "(platform)")
                ),
                setOf("g")
            ),
            Partition.BY_OWNER,
        )
        conversion.report.rejections.assert().isEmpty()
        val tenant = conversion.documents.getValue(SnapshotKey.Tenant("acme")).roundTrip()
        tenant.policies.single().id.value.assert().isEqualTo("c")
        tenant.policies.single().attachable.assert().isTrue()
        conversion.documents.getValue(
            SnapshotKey.Platform
        ).policies.map { it.id.value }.assert().containsExactly("g", "p")
        conversion.report.semanticChanges.single { it.change == "C5" }.location.assert().isEqualTo("policy:c")
    }

    @Test
    fun `grants of an app without an app permission are dropped with a warning`() {
        val conversion = convert(source(apps = listOf(app("ghost", null, "admin" to """["*"]"""))))
        conversion.report.rejections.assert().isEmpty()
        conversion.report.warnings.single().location.assert().isEqualTo("app:ghost")
        conversion.documents.keys.assert().containsExactly(SnapshotKey.Platform)
    }

    @Test
    fun `a non-global type in the global index is converted as attachable, with a warning`() {
        val conversion = convert(source(listOf("c" to policy("c")), setOf("c")))
        conversion.report.warnings.single().message.assert().contains("of type CUSTOM")
        conversion.documents.getValue(SnapshotKey.Platform).policies.single().attachable.assert().isTrue()
    }

    @Test
    fun `condition shapes of D1 to D3 and C1a and C1c are reported even while conditions are rejected`() {
        val report = convert(
            source(
                listOf(
                    "p" to policy(
                        "p",
                        condition = """{"authenticated":{}}""",
                        statements = listOf(
                            statement(condition = """{"bool":{"and":[{"inRole":{"value":"admin","negate":true}}]}}""", effect = "deny"),
                            statement(condition = """{"eq":{"part":"request.attributes.owner","value":""}}"""),
                            statement(condition = """{"regular":{"part":"request.path","pattern":"^/a$"}}"""),
                            statement(condition = """{"spel":{"value":"true"}}"""),
                        ),
                    ),
                ),
            ),
        ).report
        val changes = report.semanticChanges.groupBy({ it.change }, { it.location })
        changes.getValue("D1").single().assert().isEqualTo("policy:p#$.statements[0].condition.bool.and[0].inRole")
        report.semanticChanges.single { it.change == "D1" }.shape.assert().contains("ALLOW direction")
        changes.getValue("D2").single().assert().isEqualTo("policy:p#$.statements[1].condition.eq")
        changes.getValue("D3").single().assert().isEqualTo("policy:p#$.statements[2].condition.regular")
        changes.getValue(
            "C1a"
        ).assert().containsExactlyInAnyOrder("policy:p#$.statements[2]", "policy:p#$.statements[3]")
        changes.getValue("C1c").single().assert().isEqualTo("policy:p#$.condition")
    }

    @Test
    fun `a missing schema marker is not 7x data`() {
        convert(source(marker = null)).report.rejections.single().message.assert().contains("[absent]")
    }

    @Test
    fun `an export without a marker and without data converts to an empty platform document`() {
        val conversion = converter.convert(
            source(marker = null).let {
                Source7x(it.policies, it.globalIndex, it.apps, null, "global", "x")
            }
        )
        conversion.report.accepted.assert().isTrue()
        conversion.documents.getValue(SnapshotKey.Platform).policies.assert().isEmpty()
    }
}
