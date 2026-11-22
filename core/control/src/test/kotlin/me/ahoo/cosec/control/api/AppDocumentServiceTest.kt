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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.local.LocalControlService
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * `CTL-58`, `CTL-65`: an app document's change on the service profile and through the facade, analyzed as anyone's. Which
 * catalog changes a service publishes alone, and which wait for a person; the platform document stays outside.
 */
class AppDocumentServiceTest {
    private val fixture = ControlPlaneTest()
    private val port = ControlHttp(fixture.plane, ControlProfile.SERVICE)
    private val iam = fixture.engine.security(TenantId.PLATFORM, "svc-iam")
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")
    private val orders = SnapshotKey.App("orders")
    private val publication = Publication(
        "bootstrap",
        ProvenanceKind.BOOTSTRAP,
        Actor(TenantId.PLATFORM, PrincipalId("plato"))
    )

    init {
        runBlocking {
            // A store whose open-change sets are kept, as a new or converted one is: the listing answers (CTL-54).
            fixture.memory.keepOpenChanges()
            // svc-iam writes, stages and publishes the orders app's document, and grants on its scopes; it approves nothing.
            val head = fixture.revisions.head(SnapshotKey.Platform)!!
            val policy = PolicyDocumentCodec.decodePolicy(
                CoSecJson.mapper.readTree(
                    """{"id":"iam","statements":[{"effect":"allow","action":["cosec:read","cosec:write",""" +
                        """"cosec:publish","cosec:skip-shadow"],"resource":["doc/app/orders","doc/app/orders/**"]},""" +
                        """{"effect":"allow","action":"cosec:grant","resource":"scope/**"}]}""",
                ),
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
            fixture.revisions.publish(
                orders,
                null,
                PolicyDocumentCodec.decode(
                    DocumentKind.APP,
                    "orders",
                    (
                        """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","catalog":{"groups":[""" +
                            """{"name":"orders","permissions":[{"id":"read","action":"order:read","resource":"order/**"},""" +
                            """{"id":"cancel","action":"order:cancel","resource":"order/**"}]}]},""" +
                            """"roles":[{"id":"viewer","permissions":["read"]},{"id":"clerk","permissions":["read"]}]}"""
                        ).encodeToByteArray(),
                ),
                publication,
            )
        }
    }

    private fun call(method: String, path: String, body: String?, security: RequestSecurity = iam): Pair<Int, JsonNode> =
        runBlocking {
            val response = port.handle(
                ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()),
                security
            )
            response.status to CoSecJson.mapper.readTree(response.body.decodeToString().ifEmpty { "{}" })
        }

    private fun head(key: SnapshotKey = orders) = runBlocking { fixture.revisions.head(key) }!!.revision

    private fun request(vararg operations: String, key: String = "app:orders", base: Long = head()) =
        """{"document":"$key","base":$base,"note":"n","operations":[${operations.joinToString(",")}]}"""

    private fun reasons(change: JsonNode): List<String> =
        change.get("escalations")?.values()?.map { it.get("reason").asString() }.orEmpty()

    /** [body] proposed, its window skipped, and published when it is READY: the state it is left in, and its escalations. */
    private fun through(body: String, security: RequestSecurity = iam): Pair<String, List<String>> {
        val (status, proposed) = call("POST", "/changes", body, security)
        status.assert().describedAs("$proposed").isEqualTo(201)
        val id = proposed.get("id").asString()
        val (code, staged) = call("POST", "/changes/$id:skip-shadow", """{"reason":"service"}""", security)
        code.assert().describedAs("proposed $proposed staged $staged").isEqualTo(200)
        val state = staged.get("state").asString()
        if (state != "READY") {
            call("POST", "/changes/$id:abandon", "{}", security)
            return state to reasons(staged)
        }
        val published = call("POST", "/changes/$id:publish", "{}", security).second
        return published.get("state").asString() to reasons(published)
    }

    private val wider = """{"op":"putCatalog","catalog":{"groups":[{"name":"orders","permissions":[""" +
        """{"id":"read","action":"order:read","resource":"order/**"},""" +
        """{"id":"cancel","action":"order:cancel","resource":"order/**"},""" +
        """{"id":"export","action":"order:export","resource":"order/**"}]}]}}"""

    private val narrower = """{"op":"putCatalog","catalog":{"groups":[{"name":"orders","permissions":[""" +
        """{"id":"read","action":"order:read","resource":"order/**"}]}]}}"""

    private val facade = LocalControlService(fixture.plane, IdentityGate())

    @Test
    fun `a service proposes and checks an app document's change, on the port and through the facade - CTL-58, CTL-65`() {
        val body = request(wider)
        val (checkStatus, checked) = call("POST", "/changes:check", body)
        checkStatus.assert().isEqualTo(200)
        reasons(checked).assert().containsExactly("NOT_ANALYZED")
        val (status, proposed) = call("POST", "/changes", body)
        status.assert().isEqualTo(201)
        reasons(proposed).assert().isEqualTo(reasons(checked))
        val id = proposed.get("id").asString()
        // Its change is served by id and listed, as a tenant document's is.
        call("GET", "/changes/$id", null).first.assert().isEqualTo(200)
        val listed = runBlocking {
            port.handle(ControlHttpRequest("GET", "/changes", mapOf("document" to "app:orders"), null), iam)
        }
        listed.status.assert().isEqualTo(200)
        listed.body.decodeToString().assert().contains(id)
        call("POST", "/changes/$id:abandon", "{}").first.assert().isEqualTo(200)
        // The facade reaches the same route, and answers what the route does.
        val caller = ControlCaller.of(iam.securityContext)
        val viaFacade = CoSecJson.mapper.readTree(runBlocking { facade.check(caller, request(wider)) })
        reasons(viaFacade).assert().isEqualTo(reasons(checked))
        val proposedViaFacade = CoSecJson.mapper.readTree(runBlocking { facade.propose(caller, request(wider)) })
        proposedViaFacade.get("state").asString().assert().isEqualTo("CHECKED")
        reasons(proposedViaFacade).assert().containsExactly("NOT_ANALYZED")
    }

    @Test
    fun `what a service publishes alone in an app document, and what waits for a person - CTL-58`() {
        // Anything but a removal of an entry is analyzed as the trust root's alone to publish: a catalog widened or
        // narrowed, a role put. The service holds no cosec:approve, so each waits.
        through(request(wider)).assert().isEqualTo("AWAITING_APPROVAL" to listOf("NOT_ANALYZED"))
        through(request(narrower)).assert().isEqualTo("AWAITING_APPROVAL" to listOf("NOT_ANALYZED"))
        through(request("""{"op":"putRole","role":{"id":"auditor","permissions":["read"]}}"""))
            .assert().isEqualTo("AWAITING_APPROVAL" to listOf("NOT_ANALYZED"))
        // A role removed grants nothing anew: it publishes alone.
        val before = head()
        through(request("""{"op":"removeRole","id":"viewer"}""")).assert().isEqualTo("PUBLISHED" to emptyList<String>())
        head().assert().isEqualTo(before + 1)
        // The trust root, a platform principal holding cosec:approve, publishes a widened catalog alone, as on the
        // operators' port: the profile narrows what is served, never what a caller may do.
        through(request(wider), plato).assert().isEqualTo("PUBLISHED" to emptyList<String>())
    }

    @Test
    fun `the platform document stays outside the service profile, proposed or checked - CTL-58, CTL-65`() {
        val body = request(key = "platform", base = head(SnapshotKey.Platform))
        call("POST", "/changes", body, plato).first.assert().isEqualTo(404)
        call("POST", "/changes:check", body, plato).first.assert().isEqualTo(404)
        val caller = ControlCaller.of(plato.securityContext)
        listOf<suspend () -> String>({ facade.propose(caller, body) }, { facade.check(caller, body) }).forEach { call ->
            val refused = runBlocking {
                try {
                    call()
                    null
                } catch (e: ControlRefusedException) {
                    e
                }
            }
            refused!!.status.assert().isEqualTo(404)
        }
    }
}
