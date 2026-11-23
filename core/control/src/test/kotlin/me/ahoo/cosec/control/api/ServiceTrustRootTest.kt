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
import me.ahoo.cosec.control.sdk.ControlService
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
 * `CTL-81`: no one is the trust root through the service profile, on the service port or through the facade. There the
 * caller's identity is only stated, by whatever workload the network admitted, so a change made through it is always
 * analyzed, whoever is stated; what the analysis cannot prove waits for a person, who approves on the operators' port.
 * The same request through the operators' profile keeps the trust root's exemption, and the role class still applies.
 */
class ServiceTrustRootTest {
    private val fixture = ControlPlaneTest()
    private val service = ControlHttp(fixture.plane, ControlProfile.SERVICE)
    private val operators = ControlHttp(fixture.plane)
    private val facade = LocalControlService(fixture.plane, IdentityGate())
    private val iam = fixture.engine.security(TenantId.PLATFORM, "svc-iam")

    /** A platform administrator holding cosec:approve: the trust root on the operators' port. */
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")
    private val orders = SnapshotKey.App("orders")
    private val acme = SnapshotKey.Tenant("acme")
    private val publication = Publication(
        "bootstrap",
        ProvenanceKind.BOOTSTRAP,
        Actor(TenantId.PLATFORM, PrincipalId("plato"))
    )

    init {
        runBlocking {
            // svc-iam holds the role class on tenants' documents, and writes the orders app's; it approves nothing.
            val head = fixture.revisions.head(SnapshotKey.Platform)!!
            val policy = PolicyDocumentCodec.decodePolicy(
                CoSecJson.mapper.readTree(
                    """{"id":"iam","statements":[{"effect":"allow","action":["cosec:read","cosec:write",""" +
                        """"cosec:publish","cosec:skip-shadow","cosec:manage-roles"],""" +
                        """"resource":["doc/tenant/**","doc/app/orders","doc/app/orders/**"]},""" +
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
                            """{"name":"orders","permissions":[{"id":"read","action":"order:read","resource":"order/**"}]}]},""" +
                            """"roles":[{"id":"viewer","permissions":["read"]}]}"""
                        ).encodeToByteArray(),
                ),
                publication,
            )
        }
    }

    private fun call(
        http: ControlHttp,
        method: String,
        path: String,
        body: String?,
        security: RequestSecurity,
    ): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()), security)
        response.status to CoSecJson.mapper.readTree(response.body.decodeToString().ifEmpty { "{}" })
    }

    private fun head(key: SnapshotKey) = runBlocking { fixture.revisions.head(key) }!!.revision

    private fun request(key: SnapshotKey, vararg operations: String) =
        """{"document":"$key","base":${head(key)},"note":"n","operations":[${operations.joinToString(",")}]}"""

    private fun reasons(change: JsonNode): List<String> =
        change.get("escalations")?.values()?.map { it.get("reason").asString() }.orEmpty()

    /** [body] proposed, its window skipped, published when READY (abandoned otherwise): its state and escalations. */
    private fun through(http: ControlHttp, body: String, security: RequestSecurity): Pair<String, List<String>> {
        val (status, proposed) = call(http, "POST", "/changes", body, security)
        status.assert().describedAs("$proposed").isEqualTo(201)
        val id = proposed.get("id").asString()
        val staged = call(http, "POST", "/changes/$id:skip-shadow", """{"reason":"n"}""", security).second
        val state = staged.get("state").asString()
        if (state != "READY") {
            call(http, "POST", "/changes/$id:abandon", "{}", security)
            return state to reasons(staged)
        }
        val published = call(http, "POST", "/changes/$id:publish", "{}", security).second
        return published.get("state").asString() to reasons(published)
    }

    /** The same through the facade, as [security]'s caller. */
    private fun throughFacade(body: String, security: RequestSecurity): Pair<String, List<String>> = runBlocking {
        val caller = ControlCaller.of(security.securityContext)
        val proposed = CoSecJson.mapper.readTree(facade.propose(caller, body))
        val id = proposed.get("id").asString()
        val staged = CoSecJson.mapper.readTree((facade as ControlService).skipShadow(caller, id, "n"))
        val state = staged.get("state").asString()
        if (state != "READY") {
            facade.abandon(caller, id)
            return@runBlocking state to reasons(staged)
        }
        val published = CoSecJson.mapper.readTree(facade.publish(caller, id))
        published.get("state").asString() to reasons(published)
    }

    private val widened = """{"op":"putCatalog","catalog":{"groups":[{"name":"orders","permissions":[""" +
        """{"id":"read","action":"order:read","resource":"order/**"},""" +
        """{"id":"cancel","action":"order:cancel","resource":"order/**"}]}]}}"""

    @Test
    fun `a stated platform administrator's widened catalog waits through the service port and the facade - CTL-81`() {
        through(
            service,
            request(orders, widened),
            plato
        ).assert().isEqualTo("AWAITING_APPROVAL" to listOf("NOT_ANALYZED"))
        throughFacade(request(orders, widened), plato).assert().isEqualTo("AWAITING_APPROVAL" to listOf("NOT_ANALYZED"))
        // The same request through the operators' port: the trust root publishes it alone, as before.
        val before = head(orders)
        through(operators, request(orders, widened), plato).assert().isEqualTo("PUBLISHED" to emptyList<String>())
        head(orders).assert().isEqualTo(before + 1)
    }

    @Test
    fun `a stated platform administrator's grant on a tenant's document waits through the service port - CTL-81`() {
        val grant = arrayOf(
            """{"op":"putPolicy","policy":{"id":"night","statements":[{"effect":"allow","action":"order:read"}]}}""",
            """{"op":"putBinding","binding":{"id":"night","subject":{"role":"ops"},"app":"order","space":"*","policy":"night"}}""",
        )
        val (state, waiting) = through(service, request(acme, *grant), plato)
        state.assert().isEqualTo("AWAITING_APPROVAL")
        waiting.assert().contains("CROSS_TENANT")
        through(operators, request(acme, *grant), plato).assert().isEqualTo("PUBLISHED" to emptyList<String>())
    }

    @Test
    fun `a stated tenant principal publishes alone what its stated roles hold, through the service port - CTL-58, CTL-81`() {
        // DR1-E-4: whatever workload the network admits to the service port states who acts, roles included. The change
        // is analyzed as that principal: what the stated roles hold is contained and publishes alone, with no person.
        val tina = fixture.engine.security(TenantId("acme"), "tina", roles = listOf("admin", "ops@team-a"))
        val held = """{"op":"putBinding","binding":{"id":"readers","subject":{"role":"reader"},"app":"order",""" +
            """"space":"team-a","policy":"orders"}}"""
        val before = head(acme)
        through(service, request(acme, held), tina).assert().isEqualTo("PUBLISHED" to emptyList<String>())
        head(acme).assert().isEqualTo(before + 1)
        // What the stated roles do not hold waits for a person, as it does on the operators' port.
        val wider = """{"op":"putBinding","binding":{"id":"everywhere","subject":{"role":"reader"},"app":"order",""" +
            """"space":"*","policy":"orders"}}"""
        val (state, waiting) = through(service, request(acme, wider), tina)
        state.assert().isEqualTo("AWAITING_APPROVAL")
        waiting.assert().contains("NOT_COVERED")
    }

    @Test
    fun `a holder's roles-only change still publishes through the service port, by the role class - CTL-81`() {
        val role = """{"op":"putRole","role":{"id":"night","app":"orders","permissions":["read"]}}"""
        through(service, request(acme, role), iam).assert().isEqualTo("PUBLISHED" to listOf("ROLE_CLASS"))
    }

    @Test
    fun `a removal that grants nothing still publishes alone through the service port - CTL-81`() {
        through(service, request(orders, """{"op":"removeRole","id":"viewer"}"""), plato)
            .assert().isEqualTo("PUBLISHED" to emptyList<String>())
    }

    @Test
    fun `the profile is the request's, not the change's, one readied on the operators' port waits when published on the service port - CTL-81`() {
        val (status, proposed) = call(operators, "POST", "/changes", request(orders, widened), plato)
        status.assert().isEqualTo(201)
        reasons(proposed).assert().isEmpty()
        val id = proposed.get("id").asString()
        call(operators, "POST", "/changes/$id:skip-shadow", """{"reason":"n"}""", plato).second.get("state").asString()
            .assert().isEqualTo("READY")
        val before = head(orders)
        val published = call(service, "POST", "/changes/$id:publish", "{}", plato).second
        published.get("state").asString().assert().describedAs("$published").isEqualTo("AWAITING_APPROVAL")
        reasons(published).assert().containsExactly("NOT_ANALYZED")
        head(orders).assert().isEqualTo(before)
    }
}
