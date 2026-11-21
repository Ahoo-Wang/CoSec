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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * CTL-52 (R2C-6): a store that cannot answer never reads as an empty answer. Every route of the control API answers 503,
 * without detail, to a caller it is for, and both maintenance passes do nothing: no window is closed, nothing is reclaimed.
 */
class StoreOutageTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    private val proposal = """{"document":"tenant:acme","base":1,"note":"more",""" +
        """"operations":[{"op":"putPolicy","policy":{"id":"more","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
    private val request = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order",""" +
        """"space":"team-a","action":"order:read","resource":"order/1","time":"2026-11-01T00:00:00Z"}"""
    private val desired = """{"mode":"MERGE","note":"apply","document":{"schema":2,"revision":1,""" +
        """"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}}"""

    private class Route(val method: String, val path: String, val body: String? = null, val query: Map<String, String> = emptyMap())

    /** One request of each route of the control API, each well formed, so only the store can refuse it. */
    private fun routes(id: String) = listOf(
        Route("POST", "/changes", proposal),
        Route("GET", "/changes/$id"),
        Route("POST", "/changes/$id:rebase", "{}"),
        Route("POST", "/changes/$id:shadow", "{}"),
        Route("POST", "/changes/$id:skip-shadow", """{"reason":"r"}"""),
        Route("POST", "/changes/$id:approve", """{"candidate":"d"}"""),
        Route("POST", "/changes/$id:reject", """{"reason":"r"}"""),
        Route("POST", "/changes/$id:abandon", "{}"),
        Route("POST", "/changes/$id:publish", "{}"),
        Route("GET", "/documents/platform"),
        Route("GET", "/documents/tenant:acme"),
        Route("GET", "/documents/tenant:acme/revisions"),
        Route("GET", "/documents/tenant:acme/revisions/1"),
        Route("GET", "/documents/tenant:acme/revisions/1/propagation"),
        Route("GET", "/documents/tenant:acme/diff", query = mapOf("from" to "1", "to" to "1")),
        Route("POST", "/documents/tenant:acme:apply", desired),
        Route("POST", "/documents/tenant:acme:rollback", """{"revision":1,"note":"back"}"""),
        Route("POST", "/simulate", """{"request":$request}"""),
        Route("POST", "/explain", """{"request":$request,"at":{"platform":1,"tenant":1}}"""),
        Route(
            "POST",
            "/analyze",
            """{"query":{"tenant":"acme","app":"order","space":"team-a","action":"order:read","resource":"order/1"}}""",
        ),
        Route("POST", "/break-glass", """{"principal":"bob","expiresAt":"2030-01-01T00:00:00Z","reason":"r"}"""),
        Route("POST", "/break-glass/$id:approve", """{"candidate":"d"}"""),
        Route("DELETE", "/break-glass/glass", query = mapOf("reason" to "r")),
    )

    private fun call(route: Route, security: RequestSecurity): Pair<Int, Boolean> = runBlocking {
        val response = http.handle(
            ControlHttpRequest(route.method, route.path, route.query, route.body?.encodeToByteArray()),
            security,
        )
        response.status to CoSecJson.mapper.readTree(response.body).has("detail")
    }

    private fun proposed(): String = runBlocking {
        fixture.changes.propose(fixture.alice, SnapshotKey.Tenant("acme"), 1, emptyList(), "in flight").id.value
    }

    @Test
    fun `every route answers 503 without detail while the store cannot answer`() {
        val id = proposed()
        fixture.down = true
        // Each route as a caller it is for: a refusal the rules give before any read (alice and the platform's
        // document, tenant isolation) is not the store's to answer.
        for ((caller, tenantRoutes) in listOf(plato to false, alice to true)) {
            for (route in routes(id).filter { !tenantRoutes || "platform" !in it.path && "break-glass" !in it.path }) {
                val (status, detailed) = call(route, caller)
                status.assert().describedAs("${route.method} ${route.path}").isEqualTo(503)
                detailed.assert().describedAs("${route.method} ${route.path}").isFalse()
            }
        }
    }

    @Test
    fun `both maintenance passes do nothing while the store cannot answer`() = runBlocking<Unit> {
        val id = ChangeId(proposed())
        fixture.changes.startShadow(fixture.alice, id).state.assert().isEqualTo(ChangeState.SHADOWING)
        val history = fixture.plane.history(fixture.alice, SnapshotKey.Tenant("acme")).size
        fixture.down = true
        fixture.maintenance.reclaim().assert().isEmpty()
        fixture.maintenance.closeShadowWindows().assert().isEmpty()
        fixture.down = false
        // The window the pass could have closed is still open, and nothing of the history went.
        fixture.changes.get(id).state.assert().isEqualTo(ChangeState.SHADOWING)
        fixture.plane.history(fixture.alice, SnapshotKey.Tenant("acme")).size.assert().isEqualTo(history)
    }
}
