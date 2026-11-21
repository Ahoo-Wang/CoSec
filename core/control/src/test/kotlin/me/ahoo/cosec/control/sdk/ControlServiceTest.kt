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

package me.ahoo.cosec.control.sdk

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlPlaneTest
import me.ahoo.cosec.control.api.ControlProfile
import me.ahoo.cosec.control.change.ChangeOrigin
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration

/**
 * `CTL-65`: the control plane's facade decides every call for the principal its caller states, by the policies, and
 * records it, by the route's own code path. The same call through the route and through the facade answers the same
 * bytes and records the same change events; a route outside the narrow set is not reachable through the facade at
 * all; a caller is taken only from a context CoSec made, or admitted as the gate admits a forwarded identity.
 */
class ControlServiceTest {
    private val acme = SnapshotKey.Tenant("acme")

    private class Side {
        val fixture = ControlPlaneTest()
        val operators = ControlHttp(fixture.plane)

        /** The service port: the routes of the service profile, the one definition of what a service reaches. */
        val port = ControlHttp(fixture.plane, ControlProfile.SERVICE)
        val service = LocalControlService(fixture.plane, IdentityGate())
        val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

        init {
            // A store whose open-change sets are kept, as a new or converted one is: the listing answers (CTL-54).
            runBlocking { fixture.memory.keepOpenChanges() }
        }

        /** The route's answer, or its problem, as the facade's refusal carries it. */
        fun route(
            method: String,
            path: String,
            query: Map<String, String> = emptyMap(),
            body: String? = null,
            security: RequestSecurity = alice,
            http: ControlHttp = port,
        ): String =
            runBlocking {
                val response = http.handle(ControlHttpRequest(method, path, query, body?.encodeToByteArray()), security)
                "${response.status} ${response.body.decodeToString()}"
            }

        fun facade(call: suspend ControlService.(ControlCaller) -> String): String = runBlocking {
            try {
                "200 " + service.call(ControlCaller.of(alice.securityContext))
            } catch (e: ControlRefusedException) {
                "${e.status} ${e.problem}"
            }
        }

        /** The change events recorded, in no order: best-effort listeners receive them in any order. */
        fun events(): List<String> = runBlocking {
            fixture.listeners.awaitDelivered()
            fixture.events.map {
                "${it.kind} ${it.change.value} ${it.document} ${it.operator} ${it.base} ${it.candidate} ${it.note}"
            }
                .sorted()
        }
    }

    private fun proposal(id: String, base: Int) = """{"document":"tenant:acme","base":$base,"note":"$id",""" +
        """"operations":[{"op":"putPolicy","policy":{"id":"$id","statements":[{"effect":"allow","action":"order:read"}]}}]}"""

    private fun ok(answer: String, step: String = "") =
        answer.substringAfter(' ').also { answer.assert().describedAs(step).startsWith("20") }

    @Test
    fun `each call of the narrow set answers and records through the facade as through its route - CTL-65`() {
        val viaRoute = Side()
        val viaFacade = Side()
        var step = 0
        fun same(route: Side.() -> String, facade: suspend ControlService.(ControlCaller) -> String) {
            step++
            val expected = ok(viaRoute.route(), "route, step $step")
            ok(viaFacade.facade(facade), "facade, step $step").assert().describedAs("step $step").isEqualTo(expected)
        }
        same({ route("GET", "/documents/tenant:acme") }, { document(it, acme) })
        same(
            { route("GET", "/documents/tenant:acme/revisions", mapOf("limit" to "5")) },
            { revisions(it, acme, limit = 5) }
        )
        same({ route("GET", "/documents/tenant:acme/revisions/1") }, { revision(it, acme, 1) })
        same(
            { route("GET", "/documents/tenant:acme/diff", mapOf("from" to "1", "to" to "1")) },
            { diff(it, acme, 1, 1) }
        )
        same({ route("POST", "/changes", body = proposal("first", 1)) }, { propose(it, proposal("first", 1)) })
        same({ route("POST", "/changes", body = proposal("behind", 1)) }, { propose(it, proposal("behind", 1)) })
        same({ route("GET", "/changes/c1") }, { change(it, "c1") })
        same({ route("GET", "/changes", mapOf("document" to "tenant:acme", "state" to "CHECKED")) }, {
            changes(it, acme, ChangeState.CHECKED)
        })
        same(
            { route("POST", "/changes/c1:skip-shadow", body = """{"reason":"hotfix"}""") },
            { skipShadow(it, "c1", "hotfix") }
        )
        same({ route("POST", "/changes/c1:publish", body = "{}") }, { publish(it, "c1") })
        same({ route("POST", "/changes/c2:rebase", body = "{}") }, { rebase(it, "c2") })
        same({ route("POST", "/changes/c2:abandon", body = "{}") }, { abandon(it, "c2") })
        val desired = """{"mode":"MERGE","note":"apply","document":{"schema":2,"revision":2,""" +
            """"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}}"""
        same({ route("POST", "/documents/tenant:acme:apply", body = desired) }, { apply(it, acme, desired) })
        // A service starts its change's window, reads it, and publishes with the window's evidence (CTL-65).
        same({ route("POST", "/changes", body = proposal("windowed", 2)) }, { propose(it, proposal("windowed", 2)) })
        same({ route("POST", "/changes/c4:shadow", body = "{}") }, { startShadow(it, "c4") })
        same({ route("GET", "/changes/c4") }, { change(it, "c4") })
        viaFacade.events().assert().isEqualTo(viaRoute.events())
        viaFacade.events().assert().isNotEmpty()
    }

    @Test
    fun `a refusal through the facade is the route's problem, and records nothing - CTL-65`() {
        val viaRoute = Side()
        val viaFacade = Side()
        val vera = viaFacade.fixture.engine.security(ControlPlaneTest.ACME, "vera", roles = listOf("viewer"))
        val routeVera = viaRoute.fixture.engine.security(ControlPlaneTest.ACME, "vera", roles = listOf("viewer"))
        val refused = runBlocking {
            viaRoute.port.handle(
                ControlHttpRequest("POST", "/changes", emptyMap(), proposal("x", 1).encodeToByteArray()),
                routeVera
            )
        }
        refused.status.assert().isEqualTo(403)
        val error = runBlocking {
            runCatching {
                viaFacade.service.propose(
                    ControlCaller.of(vera.securityContext),
                    proposal("x", 1)
                )
            }.exceptionOrNull()
        } as ControlRefusedException
        error.status.assert().isEqualTo(403)
        error.code.assert().isEqualTo("FORBIDDEN")
        error.detail.assert().isNull()
        // The problem is the route's, but for the request id each side generated.
        CoSecJson.mapper.readTree(
            error.problem
        ).also { (it as tools.jackson.databind.node.ObjectNode).remove("requestId") }
            .assert().isEqualTo(
                CoSecJson.mapper.readTree(
                    refused.body
                ).also { (it as tools.jackson.databind.node.ObjectNode).remove("requestId") },
            )
        viaFacade.events().assert().isEmpty()
    }

    @Test
    fun `no route outside the narrow set is reachable through the facade, nor any on the platform document - CTL-65`() {
        val side = Side()
        val plato = side.fixture.engine.security(TenantId.PLATFORM, "plato")
        val caller = ControlCaller.of(plato.securityContext)
        // A platform administrator, who may read and change the platform document on the operators' routes.
        side.route("GET", "/documents/platform", security = plato, http = side.operators).assert().startsWith("200")
        side.route("GET", "/documents/platform", security = plato).assert().startsWith("404")
        val refused = runBlocking {
            runCatching { side.service.document(caller, SnapshotKey.Platform) }.exceptionOrNull()
        } as ControlRefusedException
        refused.status.assert().isEqualTo(404)
        val platformChange = runBlocking {
            side.fixture.changes.propose(side.fixture.plato, SnapshotKey.Platform, 1, emptyList(), "platform").id.value
        }
        (
            runBlocking {
                runCatching {
                    side.service.publish(
                        caller,
                        platformChange
                    )
                }.exceptionOrNull()
            } as ControlRefusedException
            )
            .status.assert().isEqualTo(404)
        // Neither by the service port: a change of the platform document is not reachable by its id (CTL-58).
        side.route("GET", "/changes/$platformChange", security = plato).assert().startsWith("404")
        side.route("POST", "/changes/$platformChange:publish", body = "{}", security = plato).assert().startsWith("404")
        side.route("GET", "/changes", mapOf("document" to "platform"), security = plato).assert().startsWith("404")
        side.route("GET", "/changes/$platformChange", security = plato, http = side.operators).assert().startsWith("200")
    }

    @Test
    fun `the facade reaches exactly the routes the service port serves, route by route - CTL-65`() {
        val profile = ControlProfile.SERVICE
        for ((method, path) in ROUTES) {
            val served = profile.admits(method, path.trim('/').split('/'))
            served.assert().describedAs("$method $path").isEqualTo(FACADE_ROUTES.values.any { method to path in it })
        }
        // Each call of the facade is one of these routes: a call added to it is added here, or this fails.
        ControlService::class.java.declaredMethods.filter { !it.isSynthetic && !it.name.endsWith("\$default") }
            .map { it.name }.toSet().assert().isEqualTo(FACADE_ROUTES.keys)
    }

    @Test
    fun `a call through the facade is recorded as made in process - CTL-57, CTL-65`() = runBlocking<Unit> {
        val side = Side()
        val proposed = side.service.propose(ControlCaller.of(side.alice.securityContext), proposal("inside", 1))
        val id = CoSecJson.mapper.readTree(proposed).get("id").asString()
        val origin = side.fixture.changes.get(me.ahoo.cosec.control.change.ChangeId(id)).origin!!
        origin.via!!.method.assert().isEqualTo(ChangeOrigin.IN_PROCESS)
        origin.peer.assert().isNull()
        origin.asserted.assert().isFalse()
        side.fixture.listeners.awaitDelivered()
        side.fixture.events.single { it.change.value == id }.origin!!.via!!.method.assert()
            .isEqualTo(ChangeOrigin.IN_PROCESS)
        // A service caller's identity was stated, and believed: asserted, and in process.
        val service = side.service.propose(ControlCaller.service("plato"), proposal("by-service", 1))
        val byService = CoSecJson.mapper.readTree(service).get("id").asString()
        side.fixture.changes.get(me.ahoo.cosec.control.change.ChangeId(byService)).origin!!.also {
            it.asserted.assert().isTrue()
            it.via!!.method.assert().isEqualTo(ChangeOrigin.IN_PROCESS)
        }
        // The same proposal over the service port is recorded with the request's credential.
        side.route("POST", "/changes", body = proposal("outside", 1)).assert().startsWith("201")
        side.fixture.changes.get(me.ahoo.cosec.control.change.ChangeId("c3")).origin!!.via!!.method.assert()
            .isNotEqualTo(ChangeOrigin.IN_PROCESS)
    }

    @Test
    fun `a caller is taken only from a context CoSec made - CTL-65`() {
        val assembled = object : SecurityContext {
            override val tenantId = "acme"
            override val principal: CoSecPrincipal get() = error("not read")
            override val scope: SecurityScope get() = error("not read")
            override val forwardedIdentity = null
        }
        assertThrownBy<IllegalArgumentException> { ControlCaller.of(assembled) }
        val side = Side()
        RequestSecurity.of(ControlCaller.of(side.alice.securityContext).security!!.securityContext).assert().isNotNull()
    }

    @Test
    fun `a service caller acts as its platform principal, admitted as the gate admits a forwarded identity - CTL-65`() {
        val side = Side()
        ok(runBlocking { "200 " + side.service.document(ControlCaller.service("plato"), acme) })
            .assert().isEqualTo(ok(side.route("GET", "/documents/tenant:acme")))
        (
            runBlocking {
                runCatching {
                    side.service.document(
                        ControlCaller.service("nobody"),
                        acme
                    )
                }.exceptionOrNull()
            }
                as ControlRefusedException
            ).status.assert().isEqualTo(403)
        // A gate whose clock is two minutes ahead reads the service identity as older than its maximum age.
        val ahead = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2))
        val late = LocalControlService(side.fixture.plane, IdentityGate(clock = ahead))
        (
            runBlocking { runCatching { late.document(ControlCaller.service("plato"), acme) }.exceptionOrNull() }
                as ControlRefusedException
            ).status.assert().isEqualTo(401)
    }

    private companion object {
        /** Every route of the control API, by method and path shape (`ControlHttp`). */
        val ROUTES = listOf(
            "GET" to "/documents/tenant:a",
            "GET" to "/documents/app:a",
            "GET" to "/documents/platform",
            "GET" to "/documents/tenant:a/revisions",
            "GET" to "/documents/tenant:a/revisions/1",
            "GET" to "/documents/tenant:a/diff",
            "GET" to "/documents/tenant:a/revisions/1/propagation",
            "POST" to "/documents/tenant:a:apply",
            "POST" to "/documents/app:a:apply",
            "POST" to "/documents/tenant:a:rollback",
            "POST" to "/changes",
            "GET" to "/changes",
            "GET" to "/changes/c1",
            "POST" to "/changes/c1:rebase",
            "POST" to "/changes/c1:shadow",
            "POST" to "/changes/c1:skip-shadow",
            "POST" to "/changes/c1:approve",
            "POST" to "/changes/c1:reject",
            "POST" to "/changes/c1:abandon",
            "POST" to "/changes/c1:publish",
            "POST" to "/break-glass",
            "POST" to "/break-glass/c1:approve",
            "DELETE" to "/break-glass/b1",
            "POST" to "/simulate",
            "POST" to "/explain",
            "POST" to "/analyze",
        )

        /** Each call of the facade, and the routes it is (a read takes a tenant's or an app's document). */
        val FACADE_ROUTES: Map<String, List<Pair<String, String>>> = mapOf(
            "document" to listOf("GET" to "/documents/tenant:a", "GET" to "/documents/app:a"),
            "revisions" to listOf("GET" to "/documents/tenant:a/revisions"),
            "revision" to listOf("GET" to "/documents/tenant:a/revisions/1"),
            "diff" to listOf("GET" to "/documents/tenant:a/diff"),
            "apply" to listOf("POST" to "/documents/tenant:a:apply"),
            "propose" to listOf("POST" to "/changes"),
            "changes" to listOf("GET" to "/changes"),
            "change" to listOf("GET" to "/changes/c1"),
            "rebase" to listOf("POST" to "/changes/c1:rebase"),
            "startShadow" to listOf("POST" to "/changes/c1:shadow"),
            "skipShadow" to listOf("POST" to "/changes/c1:skip-shadow"),
            "abandon" to listOf("POST" to "/changes/c1:abandon"),
            "publish" to listOf("POST" to "/changes/c1:publish"),
        )
    }
}
