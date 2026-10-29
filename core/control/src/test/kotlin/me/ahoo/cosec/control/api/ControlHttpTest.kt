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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * RFC 0007 §10.2: the control API's routes take the request the host pipeline permitted, go through the control
 * plane's authorization, and answer problems that say what the caller can act on and nothing more.
 */
class ControlHttpTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        security: RequestSecurity? = alice,
        query: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest(method, path, query, body?.encodeToByteArray()), security)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    @Test
    fun `an anonymous request is a 401 and nothing else happens`() {
        val (status, body) = call("GET", "/documents/tenant:acme", security = null)
        status.assert().isEqualTo(401)
        body.get("type").asString().assert().isEqualTo("https://cosec.ahoo.me/problems/unauthorized")
        body.has("detail").assert().isFalse()
    }

    @Test
    fun `a change goes through the routes, each step authorized`() {
        val proposal = """{"document":"tenant:acme","base":1,"note":"more",""" +
            """"operations":[{"op":"putPolicy","policy":{"id":"more","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
        val (created, change) = call("POST", "/changes", proposal)
        created.assert().isEqualTo(201)
        change.get("state").asString().assert().isEqualTo("CHECKED")
        val id = change.get("id").asString()
        call("GET", "/changes/$id").first.assert().isEqualTo(200)
        // tenant-admin holds no cosec:skip-shadow: refused, without saying why.
        val (refused, problem) = call("POST", "/changes/$id:skip-shadow", """{"reason":"hurry"}""")
        refused.assert().isEqualTo(403)
        problem.get("code").asString().assert().isEqualTo("FORBIDDEN")
        problem.has("detail").assert().isFalse()
        call("POST", "/changes/$id:shadow").second.get("state").asString().assert().isEqualTo("SHADOWING")
        runBlocking { fixture.changes.shadowed(ChangeId(id)) }
        call("POST", "/changes/$id:publish").second.get("state").asString().assert().isEqualTo("PUBLISHED")
        // Publishing it again is the caller's own mistake: a 409 that says why.
        val (conflict, again) = call("POST", "/changes/$id:publish")
        conflict.assert().isEqualTo(409)
        again.get("detail").asString().assert().contains("PUBLISHED")
        // Another tenant's principal does not even learn the change exists.
        val mallory = fixture.engine.security(ControlPlaneTest.GLOBEX, "mallory", roles = listOf("admin"))
        call("GET", "/changes/$id", security = mallory).first.assert().isEqualTo(404)
    }

    @Test
    fun `rate limit operations are read from the request`() {
        val rateLimit = """{"document":"tenant:acme","base":1,"note":"limit",""" +
            """"operations":[{"op":"putRateLimit","rateLimit":{"id":"reads","action":"order:read","permitsPerSecond":5}}]}"""
        val (created, change) = call("POST", "/changes", rateLimit)
        created.assert().isEqualTo(201)
        change.get("state").asString().assert().isEqualTo("CHECKED")
    }

    @Test
    fun `a boundary operation is read from the request`() {
        val boundary = """{"document":"platform","base":1,"note":"fence",""" +
            """"operations":[{"op":"setBoundary","boundary":"fence"}]}"""
        // Read, then judged: not a 400 for a field the codec did not know.
        val (created, change) = call("POST", "/changes", boundary, security = plato)
        created.assert().isEqualTo(201)
        change.get("operations").single().get("boundary").asString().assert().isEqualTo("fence")
    }

    @Test
    fun `documents, history and diffs`() {
        call("GET", "/documents/tenant:acme").second.get("bindings").size().assert().isEqualTo(2)
        call("GET", "/documents/platform").first.assert().isEqualTo(403)
        call("GET", "/documents/platform", security = plato).first.assert().isEqualTo(200)
        call("GET", "/documents/tenant:acme/revisions").second.get(0).get("revision").asLong().assert().isEqualTo(1)
        call("GET", "/documents/tenant:acme/revisions/1").first.assert().isEqualTo(200)
        val (gone, problem) = call("GET", "/documents/tenant:acme/revisions/9")
        gone.assert().isEqualTo(410)
        problem.get("code").asString().assert().isEqualTo("GONE")
        call(
            "GET",
            "/documents/tenant:acme/diff",
            query = mapOf("from" to "1", "to" to "1")
        ).second.get("sections").asBoolean().assert().isFalse()
        call("GET", "/documents/tenant:acme/diff").first.assert().isEqualTo(400)
        call("GET", "/documents/tenant:acme/revisions/1/propagation").second.get("recent").asInt().assert().isEqualTo(0)
        val desired = """{"mode":"MERGE","note":"apply","document":{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"policies":[{"id":"x","statements":[{"effect":"allow","action":"order:read"}]}],"bindings":[]}}"""
        call("POST", "/documents/tenant:acme:apply", desired).first.assert().isEqualTo(201)
        call(
            "POST",
            "/documents/tenant:acme:rollback",
            """{"revision":1,"note":"back"}"""
        ).first.assert().isIn(201, 409)
    }

    @Test
    fun `simulate, explain and analyze over HTTP`() {
        val request = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order","space":"team-a",""" +
            """"action":"order:read","resource":"order/1","time":"2026-11-01T00:00:00Z"}"""
        val simulated = call("POST", "/simulate", """{"request":$request}""").second
        simulated.get("enforced").get("decision").asString().assert().isEqualTo("Permit")
        simulated.get("enforced").get("by").get("policy").asString().assert().isEqualTo("orders")
        call("POST", "/explain", """{"request":$request,"at":{"platform":1,"tenant":1}}""").second
            .get("decision").get("decision").asString().assert().isEqualTo("Permit")
        val query = """{"tenant":"acme","app":"order","space":"team-a","action":"order:read","resource":"order/1"}"""
        call("POST", "/analyze", """{"query":$query}""").second.get("allows").size().assert().isEqualTo(1)
    }

    /** RFC 0009 §4.2: a simulated credential states its flags; anything but a boolean is refused, never read as one. */
    @Test
    fun `simulate reads the credential's flags as booleans only`() {
        val request = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order","space":"team-a",""" +
            """"action":"order:read","resource":"order/1","time":"2026-11-01T00:00:00Z""""
        call("POST", "/simulate", """{"request":$request,"principalBindings":false,"breakGlass":false}}""")
            .second.get("enforced").get("decision").asString().assert().isEqualTo("Permit")
        for (field in listOf("principalBindings", "breakGlass")) {
            val (status, problem) = call("POST", "/simulate", """{"request":$request,"$field":"false"}}""")
            status.assert().describedAs(field).isEqualTo(400)
            problem.get("detail").asString().assert().contains("boolean")
        }
    }

    /** Review K1 and K2: a change's kind is not the request's, and a change of another tenant is a missing one. */
    @Test
    fun `a body kind is refused, and another tenant's change answers like a missing one`() {
        val proposal = """{"document":"tenant:acme","base":1,"note":"more",""" +
            """"operations":[{"op":"putPolicy","policy":{"id":"more","statements":[{"effect":"allow","action":"order:read"}]}}]"""
        val (refused, problem) = call("POST", "/changes", "$proposal,\"kind\":\"MIGRATE\"}")
        refused.assert().isEqualTo(400)
        problem.get("detail").asString().assert().contains("kind")
        val id = call("POST", "/changes", "$proposal}").second.get("id").asString()
        val mallory = fixture.engine.security(ControlPlaneTest.GLOBEX, "mallory", roles = listOf("admin"))
        val steps = mapOf(
            "rebase" to null,
            "shadow" to null,
            "skip-shadow" to """{"reason":"x"}""",
            "approve" to """{"candidate":"x"}""",
            "reject" to """{"reason":"x"}""",
            "abandon" to null,
            "publish" to null,
        )
        for ((verb, body) in steps) {
            val theirs = call("POST", "/changes/$id:$verb", body, security = mallory)
            val missing = call("POST", "/changes/c999:$verb", body, security = mallory)
            theirs.first.assert().describedAs(verb).isEqualTo(404)
            theirs.assert().describedAs(verb).isEqualTo(missing)
        }
    }

    @Test
    fun `malformed requests and unknown routes`() {
        call("POST", "/changes", "{not json").first.assert().isEqualTo(400)
        call(
            "POST",
            "/changes",
            """{"document":"tenant:acme"}"""
        ).second.get("detail").asString().assert().contains("operations")
        call("POST", "/changes", """[]""").first.assert().isEqualTo(400)
        call("GET", "/changes").first.assert().isEqualTo(400)
        call("GET", "/nowhere").first.assert().isEqualTo(404)
        call("GET", "/documents/nowhere").first.assert().isEqualTo(400)
        call("POST", "/changes/c1:explode").first.assert().isEqualTo(404)
        call(
            "POST",
            "/simulate",
            """{"request":{"tenant":"acme","action":"read","resource":"order/1"}}"""
        ).first.assert().isEqualTo(400)
    }

    @Test
    fun `break-glass over HTTP is refused without a REQUIRED change listener`() {
        val (status, problem) = call(
            "POST",
            "/break-glass",
            """{"principal":"plato","expiresAt":"2026-11-01T01:00:00Z","reason":"incident"}""",
            security = plato,
        )
        status.assert().isEqualTo(503)
        problem.has("detail").assert().isFalse()
        call("DELETE", "/break-glass/glass", security = plato).first.assert().isEqualTo(400)
        call(
            "DELETE",
            "/break-glass/glass",
            security = plato,
            query = mapOf("reason" to "done")
        ).first.assert().isIn(200, 201, 409, 422)
        call("POST", "/break-glass/c9:approve", """{"candidate":"x"}""", security = plato).first.assert().isEqualTo(404)
    }
}
