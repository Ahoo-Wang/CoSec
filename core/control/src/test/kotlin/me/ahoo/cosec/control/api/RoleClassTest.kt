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
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * `CTL-79`, the role class: a change of a tenant's document that only writes or removes roles, by a holder of
 * `cosec:manage-roles` on that document, needs no second person on account of who grants, and records the class it was
 * granted by. Anything else is decided as before: without the grant, mixed with any other operation, of an app's
 * document, without `cosec:write` or `cosec:grant`. A check answers what the proposal would, the class included.
 */
class RoleClassTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val iam = fixture.engine.security(TenantId.PLATFORM, "svc-iam")
    private val publication = Publication(
        "bootstrap",
        ProvenanceKind.BOOTSTRAP,
        Actor(TenantId.PLATFORM, PrincipalId("plato"))
    )

    /** The platform's grants to svc-iam: [actions] on tenants' (or [documents]) documents, and [grant] on their scopes. */
    private fun rights(
        actions: List<String>,
        grant: Boolean = true,
        documents: String = "doc/tenant/**"
    ) = runBlocking {
        val head = fixture.revisions.head(SnapshotKey.Platform)!!
        val actionList = actions.joinToString(",") { "\"$it\"" }
        val statements = buildList {
            add("""{"effect":"allow","action":[$actionList],"resource":["$documents"]}""")
            if (grant) add("""{"effect":"allow","action":"cosec:grant","resource":"scope/**"}""")
        }.joinToString(",")
        val policy = PolicyDocumentCodec.decodePolicy(
            CoSecJson.mapper.readTree("""{"id":"iam","statements":[$statements]}""")
        )
        val binding = PolicyDocumentCodec.decodeBinding(
            CoSecJson.mapper.readTree(
                """{"id":"svc-iam","subject":{"principal":"svc-iam","tenant":"(platform)"},"app":"cosec-control",""" +
                    """"space":"","policy":"iam"}""",
            ),
            DocumentKind.PLATFORM,
            "",
        )
        val document = head.document
        fixture.revisions.publish(
            SnapshotKey.Platform,
            head,
            document.copy(policies = document.policies + policy, bindings = document.bindings + binding)
                .at(head.revision + 1, Instant.now()),
            publication,
        )
        // The app whose catalog the tenant's roles take their permissions from.
        val app = SnapshotKey.App("orders")
        fixture.revisions.publish(
            app,
            fixture.revisions.head(app),
            PolicyDocumentCodec.decode(
                DocumentKind.APP,
                "orders",
                (
                    """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","catalog":{"groups":[{"name":"orders",""" +
                        """"permissions":[{"id":"read","action":"order:read","resource":"order/**"}]}]}}"""
                    ).encodeToByteArray(),
            ),
            publication,
        )
    }

    private val projector = listOf("cosec:read", "cosec:write", "cosec:publish", "cosec:skip-shadow")
    private val holder = projector + "cosec:manage-roles"

    private fun call(method: String, path: String, body: String? = null): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()), iam)
        response.status to CoSecJson.mapper.readTree(response.body.decodeToString().ifEmpty { "{}" })
    }

    private fun head(key: String = "acme") = runBlocking { fixture.revisions.head(SnapshotKey.Tenant(key)) }!!.revision

    private fun request(document: String, base: Long, vararg operations: String) =
        """{"document":"$document","base":$base,"note":"n","operations":[${operations.joinToString(",")}]}"""

    private val role = """{"op":"putRole","role":{"id":"night","app":"orders","permissions":["read"]}}"""
    private val policy = """{"op":"putPolicy","policy":{"id":"extra","statements":[{"effect":"allow","action":"order:read"}]}}"""

    private fun reasons(change: JsonNode): List<String> =
        change.get("escalations")?.values()?.map { it.get("reason").asString() }.orEmpty()

    /** Proposes [body], skips its window, and answers the state it is left in. */
    private fun stage(body: String): Pair<JsonNode, String> {
        val (status, proposed) = call("POST", "/changes", body)
        status.assert().describedAs("$proposed").isEqualTo(201)
        val id = proposed.get("id").asString()
        val staged = call("POST", "/changes/$id:skip-shadow", """{"reason":"projected"}""").second
        return proposed to staged.get("state").asString()
    }

    @Test
    fun `a holder's roles-only change of a tenant's document publishes with no person, and names the class - CTL-79`() {
        rights(holder)
        val (proposed, state) = stage(request("tenant:acme", head(), role))
        reasons(proposed).assert().containsExactly("ROLE_CLASS")
        proposed.get("escalations").single().get("detail").asString().assert()
            .contains("svc-iam@(platform)").contains("cosec:manage-roles").contains("CROSS_TENANT")
        state.assert().isEqualTo("READY")
        val published = call("POST", "/changes/${proposed.get("id").asString()}:publish", "{}").second
        published.get("state").asString().assert().isEqualTo("PUBLISHED")
        // Removing it is the class's too.
        val (_, removed) = stage(request("tenant:acme", head(), """{"op":"removeRole","id":"night"}"""))
        removed.assert().isEqualTo("READY")
    }

    @Test
    fun `without the grant, the same change waits for a person - CTL-79`() {
        rights(projector)
        val (proposed, state) = stage(request("tenant:acme", head(), role))
        reasons(proposed).assert().contains("CROSS_TENANT").doesNotContain("ROLE_CLASS")
        state.assert().isEqualTo("AWAITING_APPROVAL")
    }

    @Test
    fun `a change mixing roles with anything else is analyzed whole, and waits - CTL-79`() {
        rights(holder)
        val (proposed, state) = stage(request("tenant:acme", head(), role, policy))
        reasons(proposed).assert().contains("CROSS_TENANT").doesNotContain("ROLE_CLASS")
        state.assert().isEqualTo("AWAITING_APPROVAL")
    }

    @Test
    fun `cosec grant is still decided for a holder - CTL-79`() {
        rights(holder, grant = false)
        call("POST", "/changes", request("tenant:acme", head(), role)).first.assert().isEqualTo(403)
    }

    @Test
    fun `cosec write is still decided for a holder - CTL-79`() {
        rights(listOf("cosec:read", "cosec:manage-roles"))
        call("POST", "/changes", request("tenant:acme", head(), role)).first.assert().isEqualTo(403)
    }

    @Test
    fun `an app document's roles are outside the class - CTL-79`() {
        rights(holder, documents = "doc/**")
        val appRole = """{"op":"putRole","role":{"id":"viewer","permissions":["read"]}}"""
        val base = runBlocking { fixture.revisions.head(SnapshotKey.App("orders")) }!!.revision
        val (proposed, state) = stage(request("app:orders", base, appRole))
        reasons(proposed).assert().contains("NOT_ANALYZED").doesNotContain("ROLE_CLASS")
        state.assert().isEqualTo("AWAITING_APPROVAL")
    }

    @Test
    fun `a holder's check answers what its proposal would, with no person to wait for - CTL-79`() {
        rights(holder)
        val body = request("tenant:acme", head(), role)
        val checked = call("POST", "/changes:check", body).second
        reasons(checked).assert().containsExactly("ROLE_CLASS")
        reasons(checked).assert().isEqualTo(reasons(call("POST", "/changes", body).second))
    }

    @Test
    fun `anyone else's check answers the escalation the proposal would wait on - CTL-79`() {
        rights(projector)
        val body = request("tenant:acme", head(), role)
        val checked = call("POST", "/changes:check", body).second
        reasons(checked).assert().contains("CROSS_TENANT").doesNotContain("ROLE_CLASS")
        reasons(checked).assert().isEqualTo(reasons(call("POST", "/changes", body).second))
    }

    @Test
    fun `a role of the control plane's own app is outside the class, and waits for a person - CTL-79`() {
        rights(holder)
        val control = SnapshotKey.App("cosec-control")
        runBlocking {
            fixture.revisions.publish(
                control,
                fixture.revisions.head(control),
                PolicyDocumentCodec.decode(
                    DocumentKind.APP,
                    "cosec-control",
                    (
                        """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","catalog":{"groups":[{"name":"own",""" +
                            """"permissions":[{"id":"govern","action":["cosec:read","cosec:write","cosec:approve"],""" +
                            """"resource":"doc/tenant/**"}]}]}}"""
                        ).encodeToByteArray(),
                ),
                publication,
            )
        }
        val governors = """{"op":"putRole","role":{"id":"governors","app":"cosec-control","permissions":["govern"]}}"""
        val (proposed, state) = stage(request("tenant:acme", head(), governors))
        reasons(proposed).assert().describedAs("$proposed").contains("CROSS_TENANT").doesNotContain("ROLE_CLASS")
        state.assert().isEqualTo("AWAITING_APPROVAL")
        // A role of another app, by the same holder, is still the class's (once the waiting change gives up the slot).
        call("POST", "/changes/${proposed.get("id").asString()}:abandon", "{}").first.assert().isEqualTo(200)
        stage(request("tenant:acme", head(), role)).second.assert().isEqualTo("READY")
    }
}
