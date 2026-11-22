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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * `CTL-79`, the bounds of a role written into a tenant's document, proved before any class admits one without a person:
 * what such a role can derive, and what it can reach, whatever its app's catalog defines.
 */
class RoleClassBoundsTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val acme = SnapshotKey.Tenant("acme")

    private fun publish(key: SnapshotKey, kind: DocumentKind, owner: String, body: String) = runBlocking {
        val head = fixture.revisions.head(key)
        val text = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"$body}"""
        val document = PolicyDocumentCodec.decode(kind, owner, text.encodeToByteArray())
            .at((head?.revision ?: 0) + 1, Instant.parse("2026-11-01T00:00:00Z"))
        fixture.revisions.publish(
            key,
            head,
            document,
            Publication("bootstrap", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
        )
    }

    private fun call(
        method: String,
        path: String,
        security: RequestSecurity,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonNode> =
        runBlocking {
            val response = http.handle(ControlHttpRequest(method, path, query, body?.encodeToByteArray()), security)
            val text = response.body.decodeToString().ifEmpty { "{}" }
            response.status to CoSecJson.mapper.readTree(text)
        }

    /** The control app's catalog with a permission as wide as an app document could write: every action, every resource. */
    private fun hostileControlApp() = publish(
        SnapshotKey.App("cosec-control"),
        DocumentKind.APP,
        "cosec-control",
        ""","catalog":{"groups":[{"name":"all","permissions":[{"id":"everything","action":"cosec:*",""" +
            """"resource":["doc/**","scope/**"]}]}]}""",
    )

    @Test
    fun `a tenant's role cannot be every permission of its app - CTL-79`() {
        val text = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"roles":[{"id":"all","app":"cosec-control","permissions":"*"}]}"""
        assertThrownBy<SnapshotFormatException> {
            PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", text.encodeToByteArray())
        }.hasMessageContaining("is only for a system role")
        // The same as an operation of a change: refused before anything is checked.
        val (status, _) = call(
            "POST",
            "/changes",
            fixture.engine.security(TenantId.PLATFORM, "plato"),
            """{"document":"tenant:acme","base":1,"note":"n","operations":[{"op":"putRole","role":""" +
                """{"id":"all","app":"cosec-control","permissions":"*"}}]}""",
        )
        status.assert().isEqualTo(400)
    }

    @Test
    fun `the platform document has no roles - CTL-79`() {
        val text = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"roles":[{"id":"r","permissions":["p"]}]}"""
        assertThrownBy<SnapshotFormatException> {
            PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", text.encodeToByteArray())
        }
    }

    @Test
    fun `a tenant's role derives bindings of its own tenant to its app's permission policies, and nothing else - CTL-79`() {
        val text = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"policies":[{"id":"orders","statements":[{"effect":"allow","action":"order:*"}]}],""" +
            """"roles":[{"id":"night","app":"cosec-control","space":"dock","permissions":["everything","more"]}]}"""
        val document = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", text.encodeToByteArray())
        val effective = RoleExpansion.effective(document)
        // No policy is derived in a tenant's document: a role's permissions are its app's policies.
        effective.policies.map { it.id.value }.assert().containsExactly("orders")
        effective.bindings.size.assert().isEqualTo(2)
        effective.bindings.forEach { binding ->
            binding.subject.toString().assert().contains("acme").contains("night")
            binding.policy.value.assert().startsWith("perm:cosec-control/")
            binding.scope.app.toString().assert().contains("cosec-control")
            binding.scope.space.toString().assert().contains("dock")
        }
    }

    @Test
    fun `a projected role of the control app reaches nothing beyond its own tenant, however wide its permission - CTL-79`() {
        hostileControlApp()
        publish(
            acme,
            DocumentKind.TENANT,
            "acme",
            ""","policies":[{"id":"orders","statements":[{"effect":"allow","action":"order:*"}]}],""" +
                """"roles":[{"id":"projected","app":"cosec-control","permissions":["everything"]}]""",
        )
        val dora = fixture.engine.security(ControlPlaneTest.ACME, "dora", roles = listOf("projected"))
        // Its own tenant's document: the role is what lets dora read it.
        call("GET", "/documents/tenant:acme", dora).first.assert().isEqualTo(200)
        // Nothing else: another tenant's, the platform's, an app's; to read, to propose, to list.
        call("GET", "/documents/tenant:globex", dora).first.assert().isEqualTo(403)
        call("GET", "/documents/platform", dora).first.assert().isEqualTo(403)
        call("GET", "/documents/app:cosec-control", dora).first.assert().isEqualTo(403)
        listOf("tenant:globex", "platform", "app:cosec-control").forEach { key ->
            val body = """{"document":"$key","base":1,"note":"n","operations":[{"op":"putPolicy","policy":""" +
                """{"id":"x","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
            call("POST", "/changes", dora, body).first.assert().describedAs(key).isEqualTo(403)
        }
        val listed = call("GET", "/documents", dora, query = mapOf("kind" to "tenant"))
        listed.first.assert().isEqualTo(200)
        listed.second.get("documents").values().map { it.get("key").asString() }.assert().containsExactly("tenant:acme")
    }

    @Test
    fun `the tenant's boundary still caps what a projected role grants - CTL-79`() {
        runBlocking {
            val platform = fixture.revisions.head(SnapshotKey.Platform)!!.document
            val ceiling = PolicyDocumentCodec.decodePolicy(
                CoSecJson.mapper.readTree(
                    """{"id":"ceiling","boundary":true,"statements":[{"effect":"allow","action":"order:read"}]}""",
                ),
            )
            fixture.revisions.publish(
                SnapshotKey.Platform,
                fixture.revisions.head(SnapshotKey.Platform),
                platform.copy(policies = platform.policies + ceiling).at(platform.revision + 1, Instant.now()),
                Publication("ceiling", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
            )
        }
        publish(
            SnapshotKey.App("orders"),
            DocumentKind.APP,
            "orders",
            ""","catalog":{"groups":[{"name":"orders","permissions":[""" +
                """{"id":"read","action":"order:read","resource":"order/**"},""" +
                """{"id":"cancel","action":"order:cancel","resource":"order/**"}]}]}""",
        )
        fun simulate(action: String): String {
            val body = """{"request":{"tenant":"acme","principal":"dora","roles":["projected"],"app":"orders",""" +
                """"action":"$action","resource":"order/1"}}"""
            val (status, answer) = call("POST", "/simulate", fixture.engine.security(TenantId.PLATFORM, "plato"), body)
            status.assert().describedAs("$answer").isEqualTo(200)
            return answer.get("enforced").get("decision").asString()
        }
        val role = """"roles":[{"id":"projected","app":"orders","permissions":["read","cancel"]}]"""
        publish(acme, DocumentKind.TENANT, "acme", ",$role")
        // Without a boundary, the role grants both.
        simulate("order:read").assert().isEqualTo("Permit")
        simulate("order:cancel").assert().isEqualTo("Permit")
        // With the platform's boundary on the tenant, the role grants what the boundary allows, and no more.
        publish(acme, DocumentKind.TENANT, "acme", ""","boundary":"ceiling",$role""")
        simulate("order:read").assert().isEqualTo("Permit")
        simulate("order:cancel").assert().isNotEqualTo("Permit")
    }

    @Test
    fun `a tenant's role binds only what its app's document defines - CTL-79`() {
        val plato = fixture.engine.security(TenantId.PLATFORM, "plato")
        hostileControlApp()
        val head = runBlocking { fixture.revisions.head(acme) }!!.revision
        fun propose(operation: String): JsonNode {
            val body = """{"document":"tenant:acme","base":$head,"note":"n","operations":[$operation]}"""
            return call("POST", "/changes", plato, body).also { it.first.assert().isEqualTo(201) }.second
        }
        // A tenant cannot write a policy named as an app's permission, which a role's binding would resolve to.
        val minted = propose(
            """{"op":"putPolicy","policy":{"id":"perm:cosec-control/minted","statements":""" +
                """[{"effect":"allow","action":"cosec:*","resource":"doc/**"}]}}""",
        )
        minted.get("state").asString().assert().isEqualTo("DRAFT")
        minted.get("findings").values().map { it.get("message").asString() }.joinToString().assert()
            .contains("defined only in that app's document")
        // A role listing a permission its app does not define binds nothing: it does not publish.
        val invented =
            propose("""{"op":"putRole","role":{"id":"r","app":"cosec-control","permissions":["invented"]}}""")
        invented.get("state").asString().assert().isEqualTo("DRAFT")
        invented.get("findings").values().map { it.get("check").asString() }.assert().contains("REFERENCES")
    }

    @Test
    fun `a role of another app permits no control call, whatever its catalog says - CTL-79`() {
        // An app's catalog as hostile as it can be about the control plane: its permission names control actions.
        publish(
            SnapshotKey.App("orders"),
            DocumentKind.APP,
            "orders",
            ""","catalog":{"groups":[{"name":"govern","permissions":[{"id":"govern","action":""" +
                """["cosec:approve","cosec:read","cosec:write","cosec:grant","cosec:publish"],""" +
                """"resource":["doc/**","scope/**"]}]}]}""",
        )
        publish(
            acme,
            DocumentKind.TENANT,
            "acme",
            ""","policies":[{"id":"orders","statements":[{"effect":"allow","action":"order:*"}]}],""" +
                """"roles":[{"id":"governors","app":"orders","permissions":["govern"]}]""",
        )
        val dora = fixture.engine.security(ControlPlaneTest.ACME, "dora", roles = listOf("governors"))
        // A control request is decided in the control plane's app: a binding of another app does not apply there.
        call("GET", "/documents/tenant:acme", dora).first.assert().isEqualTo(403)
        val head = runBlocking { fixture.revisions.head(acme) }!!.revision
        val body = """{"document":"tenant:acme","base":$head,"note":"n","operations":[{"op":"putPolicy","policy":""" +
            """{"id":"x","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
        call("POST", "/changes", dora, body).first.assert().isEqualTo(403)
        call("POST", "/changes:check", dora, body).first.assert().isEqualTo(403)
    }

    @Test
    fun `a tenant's DENY still denies a member of a role the class lifted its overlap for - CTL-79`() {
        publish(
            SnapshotKey.App("orders"),
            DocumentKind.APP,
            "orders",
            ""","catalog":{"groups":[{"name":"orders","permissions":[""" +
                """{"id":"read","action":"order:read","resource":"order/**"}]}]}""",
        )
        // ivy manages acme's roles from inside acme and holds what the role grants. A DENY binds the keepers (her overlap,
        // which the class lifts) and another binds the whole tenant (it binds every grantee alike, so it is no overlap).
        publish(
            acme,
            DocumentKind.TENANT,
            "acme",
            ""","policies":[""" +
                """{"id":"keep","statements":[{"effect":"allow","action":["cosec:read","cosec:write","cosec:publish",""" +
                """"cosec:skip-shadow","cosec:manage-roles"],"resource":["doc/tenant/acme","doc/tenant/acme/**"]},""" +
                """{"effect":"allow","action":"cosec:grant","resource":"scope/**"}]},""" +
                """{"id":"reads","statements":[{"effect":"allow","action":"order:read","resource":"order/**"}]},""" +
                """{"id":"no-secrets","statements":[{"effect":"deny","action":"order:read","resource":"order/secret/**"}]},""" +
                """{"id":"no-drafts","statements":[{"effect":"deny","action":"order:read","resource":"order/draft/**"}]}],""" +
                """"bindings":[""" +
                """{"id":"keepers","subject":{"role":"keepers"},"app":"cosec-control","space":"","policy":"keep"},""" +
                """{"id":"keepers-read","subject":{"role":"keepers"},"app":"orders","space":"*","policy":"reads"},""" +
                """{"id":"no-secrets","subject":{"role":"keepers"},"app":"orders","space":"*","policy":"no-secrets"},""" +
                """{"id":"no-drafts","subject":"*","app":"orders","space":"*","policy":"no-drafts"}]""",
        )
        val ivy = fixture.engine.security(ControlPlaneTest.ACME, "ivy", roles = listOf("keepers"))
        val head = runBlocking { fixture.revisions.head(acme) }!!.revision
        val role = """{"op":"putRole","role":{"id":"night","app":"orders","permissions":["read"]}}"""
        val (status, proposed) = call(
            "POST",
            "/changes",
            ivy,
            """{"document":"tenant:acme","base":$head,"note":"n","operations":[$role]}""",
        )
        status.assert().describedAs("$proposed").isEqualTo(201)
        // The overlap with the DENY is what the class lifted, for a holder inside the tenant.
        val record = proposed.get("escalations").single()
        record.get("reason").asString().assert().describedAs("$proposed").isEqualTo("ROLE_CLASS")
        record.get("detail").asString().assert().contains("DENY_OVERLAP")
        val id = proposed.get("id").asString()
        call("POST", "/changes/$id:skip-shadow", ivy, """{"reason":"projected"}""").second.get("state").asString()
            .assert().isEqualTo("READY")
        call("POST", "/changes/$id:publish", ivy, "{}").second.get("state").asString().assert().isEqualTo("PUBLISHED")
        fun simulate(resource: String, roles: String = "\"night\""): String {
            val body = """{"request":{"tenant":"acme","principal":"dora","roles":[$roles],"app":"orders",""" +
                """"action":"order:read","resource":"$resource"}}"""
            val (code, answer) = call("POST", "/simulate", fixture.engine.security(TenantId.PLATFORM, "plato"), body)
            code.assert().describedAs("$answer").isEqualTo(200)
            return answer.get("enforced").get("decision").asString()
        }
        // The role grants what its permission names, and each DENY still binds whom it binds at decision time
        // (deny-first): the tenant-wide one denies the role's member, the keepers' one a member who is a keeper too.
        simulate("order/1").assert().isEqualTo("Permit")
        simulate("order/draft/1").assert().isEqualTo("Deny")
        simulate("order/secret/1", "\"night\",\"keepers\"").assert().isEqualTo("Deny")
    }
}
