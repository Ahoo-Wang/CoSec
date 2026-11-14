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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * RFC 0007 §10.2: the control API's routes take the request the host pipeline permitted, go through the control
 * plane's authorization, and answer problems that say what the caller can act on and nothing more.
 */
class ControlHttpTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    /** A platform principal bound to nothing: no `cosec:read` on the platform document. */
    private val pia = fixture.engine.security(TenantId.PLATFORM, "pia")

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

    /**
     * SCP-10: a change whose record does not decode, one of an app no key can name included, fails closed: a 503 without
     * detail to every caller, whatever they may read, so the answer tells nothing of why.
     */
    @Test
    fun `a change that no longer reads is a 503 to every caller, without detail`() {
        val id = runBlocking {
            fixture.changes.propose(
                fixture.plato,
                me.ahoo.cosec.spi.snapshot.SnapshotKey.Platform,
                1,
                emptyList(),
                "odd"
            )
        }.id
        fixture.unreadable += id
        for ((method, path) in listOf("GET" to "/changes/${id.value}", "POST" to "/changes/${id.value}:abandon")) {
            for (caller in listOf(plato, alice, pia)) {
                val (status, body) = call(method, path, security = caller)
                status.assert().describedAs(path).isEqualTo(503)
                body.has("detail").assert().describedAs(path).isFalse()
            }
        }
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
        // tenant-viewer may read the change and holds no cosec:skip-shadow: refused, without saying why.
        val viewer = fixture.engine.security(ControlPlaneTest.ACME, "vera", roles = listOf("viewer"))
        val (refused, problem) = call("POST", "/changes/$id:skip-shadow", """{"reason":"hurry"}""", security = viewer)
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

    /**
     * Review D5: a change's approval names its approver, when and for what; what the approver held, its token's
     * attributes and which token it was stay in the store and the change log.
     */
    @Test
    fun `an approval shows nothing of its approver's credential`() {
        val proposal = """{"document":"tenant:acme","base":1,"note":"viewers","operations":[""" +
            """{"op":"putPolicy","policy":{"id":"viewer-read","statements":""" +
            """[{"effect":"allow","action":"order:read","resource":"order/{id}"}]}},""" +
            """{"op":"putBinding","binding":{"id":"viewer-read","subject":{"role":"viewer"},"app":"order",""" +
            """"space":"*","policy":"viewer-read"}}]}"""
        val id = call("POST", "/changes", proposal).second.get("id").asString()
        call("POST", "/changes/$id:shadow").first.assert().isEqualTo(200)
        val waiting = runBlocking { fixture.changes.shadowed(ChangeId(id)) }
        waiting.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        val (status, approved) =
            call("POST", "/changes/$id:approve", """{"candidate":"${waiting.candidate}"}""", security = plato)
        status.assert().isEqualTo(200)
        val approval = approved.get("approval")
        approval.get("approver").get("principal").asString().assert().isEqualTo("plato")
        listOf("credentialId", "issuedAt", "memberships", "attributes", "principalBindings", "breakGlass").forEach {
            approval.has(it).assert().describedAs(it).isFalse()
            call("GET", "/changes/$id").second.get("approval").has(it).assert().describedAs(it).isFalse()
        }
        runBlocking { fixture.changes.get(ChangeId(id)) }.approval!!.credentialId.assert().isEqualTo("token-plato")
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
        call("GET", "/documents/tenant:acme").second.get("bindings").size().assert().isEqualTo(3)
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
    fun `a rollback carries the confirmations it is given`() {
        val body = """{"revision":1,"note":"back","confirmations":[{"policy":"p","statement":0,"digest":"d"}]}"""
        val (created, change) = call("POST", "/documents/tenant:acme:rollback", body)
        created.assert().describedAs("$change").isEqualTo(201)
        change.get("confirmations").single().get("digest").asString().assert().isEqualTo("d")
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

    /**
     * RC1-52 (`IDN-10`): a control request whose path, query or body holds a string with an unpaired surrogate is a 400,
     * before any route reads it: a JSON escape (`"\uD800"`) writes one, and a Redis key would write it as `?`, so a
     * change of tenant `beta\uD800` would be one of `beta?`. Nothing is changed. A surrogate pair is a character as any.
     */
    @Test
    fun `a request holding an unpaired surrogate is a 400, wherever it holds it`() {
        val made = fixture.ids.get()
        fun refused(response: Pair<Int, JsonNode>, what: String) {
            response.first.assert().describedAs(what).isEqualTo(400)
            response.second.get("detail").asString().assert().describedAs(what).contains("unpaired UTF-16 surrogate")
        }
        val request = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order","space":"team-a",""" +
            """"action":"order:read","resource":"order/1","time":"2026-11-01T00:00:00Z"}"""
        val policy = """{"id":"more","statements":[{"effect":"allow","action":"order:read"}]}"""
        val proposal = """{"document":"tenant:acme","base":1,"operations":[{"op":"putPolicy","policy":$policy}]}"""
        for (escape in listOf("\\uD800", "\\uDC00")) {
            refused(
                call("POST", "/simulate", """{"request":${request.replace("\"acme\"", "\"acme$escape\"")}}"""),
                "tenant"
            )
            refused(
                call("POST", "/simulate", """{"request":${request.replace("\"bob\"", "\"bob$escape\"")}}"""),
                "principal"
            )
            refused(
                call("POST", "/simulate", """{"request":${request.replace("ops@team-a", "ops@team-a$escape")}}"""),
                "role"
            )
            refused(call("POST", "/changes", proposal.replace("tenant:acme", "tenant:beta$escape")), "document key")
            refused(call("POST", "/changes", proposal.replace("\"more\"", "\"more$escape\"")), "policy id")
            // Jackson's parser refuses such a field name itself: not JSON, a 400 as well.
            call(
                "POST",
                "/changes",
                proposal.replace("{\"op\"", "{\"x$escape\":1,\"op\"")
            ).first.assert().isEqualTo(400)
            refused(
                call(
                    "POST",
                    "/break-glass",
                    """{"principal":"plato$escape","expiresAt":"2026-11-01T01:00:00Z","reason":"x"}""",
                    security = plato
                ),
                "break-glass principal",
            )
        }
        refused(call("GET", "/documents/tenant:beta\uD800"), "path")
        refused(call("GET", "/changes/c1\uDC00"), "change id")
        refused(call("GET", "/changes/c1", query = mapOf("x\uD800" to "y")), "query name")
        refused(
            call("DELETE", "/break-glass/glass", security = plato, query = mapOf("reason" to "done\uD800")),
            "query"
        )
        fixture.ids.get().assert().describedAs("no change was made").isEqualTo(made)

        val paired = call("POST", "/simulate", """{"request":${request.replace("\"bob\"", "\"bob\\uD83D\\uDE00\"")}}""")
        paired.first.assert().isEqualTo(200)
    }

    /** RC1-52: the bytes of a surrogate half in UTF-8 form are no JSON string, and a change id cannot hold one either. */
    @Test
    fun `a surrogate half in UTF-8 form, and a change id with one, are refused`() {
        val request = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order","space":"team-a",""" +
            """"action":"order:read","resource":"order/1","time":"2026-11-01T00:00:00Z"}"""
        val raw = """{"request":${request.replace("\"acme\"", "\"acme\u0000\"")}}""".encodeToByteArray()
        val index = raw.indexOf(0)
        val cesu = raw.copyOfRange(0, index) + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) +
            raw.copyOfRange(index + 1, raw.size)
        runBlocking {
            http.handle(
                ControlHttpRequest("POST", "/simulate", emptyMap(), cesu),
                alice
            )
        }.status.assert().isEqualTo(400)
        val changeId = runCatching { ChangeId("c1\uD800") }.exceptionOrNull()
        changeId.assert().isInstanceOf(IllegalArgumentException::class.java)
        changeId!!.message!!.assert().contains("[c1\\uD800]")
    }

    /** RC1-44 (`DOC-5`): a request body with a duplicate key is refused, never read as whichever value came last. */
    @Test
    fun `a body with a duplicate key is refused`() {
        val proposal = """{"document":"tenant:acme","base":1,"note":"more",""" +
            """"operations":[{"op":"putPolicy","policy":{"id":"more","statements":[{"effect":"allow","action":"order:read"}]}}]"""
        call("POST", "/changes", "$proposal,\"note\":\"again\"}").first.assert().isEqualTo(400)
        call("POST", "/changes", "$proposal}").first.assert().isEqualTo(201)
    }

    /**
     * Review M1 of #1240: only a route's exact path is served, so a rule that names the exact paths, as the documented
     * per-tenant rate limit does, covers every request that reaches one: a trailing `/` or an empty segment is a 404.
     */
    @Test
    fun `a path with a trailing slash or an empty segment is served by no route`() {
        val simulate = """{"request":{"tenant":"acme","principal":"bob","action":"order:read","resource":"order/1"}}"""
        call("POST", "/simulate", simulate).first.assert().isEqualTo(200)
        call("GET", "/documents/tenant:acme").first.assert().isEqualTo(200)
        val paths = listOf(
            "POST" to "/simulate/",
            "POST" to "//simulate",
            "POST" to "/simulate//",
            "POST" to "simulate",
            "POST" to "/explain/",
            "POST" to "/changes/",
            "GET" to "/documents//tenant:acme",
            "GET" to "/documents/tenant:acme/",
            "GET" to "/",
            // R2-M1 of #1240's review: a leaf route followed by any segment is no route either.
            "POST" to "/simulate/x",
            "POST" to "/simulate/x/y",
            "POST" to "/explain/x",
            "POST" to "/analyze/x",
            "POST" to "/documents/tenant:acme:apply/x",
            "POST" to "/documents/tenant:acme:rollback/x",
            "GET" to "/documents/tenant:acme/diff/x",
            "GET" to "/documents/tenant:acme/revisions/1/propagation/x",
            "POST" to "/changes/c1:publish/x",
            "POST" to "/break-glass/c1:approve/x",
            "DELETE" to "/break-glass/glass/x",
        )
        for ((method, path) in paths) {
            val (status, problem) = call(method, path, simulate.takeIf { method == "POST" })
            status.assert().describedAs("$method $path").isEqualTo(404)
            problem.has("detail").assert().isFalse()
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

    /**
     * R4-1, `BRK-11`: removing a break-glass binding answers 200 only once the binding no longer grants; a revoke that
     * leaves it granting is a 409 that names its change's state, never a success; a binding the head does not have is a
     * 404 (review F-1).
     */
    @Test
    fun `a break-glass revoke answers 200 only once the binding no longer grants`() {
        runBlocking {
            val head = fixture.plane.read(fixture.plato, SnapshotKey.Platform)
            val glass = Binding(
                BindingId("glass"),
                SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
                BindingScope(Match.Any, Match.Any),
                BuiltinPolicies.ROOT,
                Instant.parse("2026-11-01T01:00:00Z").toEpochMilli(),
                breakGlass = true,
            )
            // An ordinary binding with an expiry, which has passed.
            val contractor = Binding(
                BindingId("contractor"),
                SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("carl")),
                BindingScope(Match.Any, Match.Any),
                PolicyId("no-delete"),
                Instant.parse("2026-10-31T23:00:00Z").toEpochMilli(),
            )
            fixture.bootstrap(SnapshotKey.Platform, head.policies, head.bindings + glass + contractor)
        }
        // F-1: a mistyped id is a 404 without detail, and leaves no change behind.
        val made = fixture.ids.get()
        val (missing, notFound) = call(
            "DELETE",
            "/break-glass/glas",
            security = plato,
            query = mapOf("reason" to "done")
        )
        missing.assert().isEqualTo(404)
        notFound.has("detail").assert().isFalse()
        fixture.ids.get().assert().isEqualTo(made)
        val (revoked, change) = call(
            "DELETE",
            "/break-glass/glass",
            security = plato,
            query = mapOf("reason" to "done")
        )
        revoked.assert().isEqualTo(200)
        change.get("state").asString().assert().isEqualTo("PUBLISHED")
        // Revoked, the id is no longer on the head.
        call("DELETE", "/break-glass/glass", security = plato, query = mapOf("reason" to "done")).first
            .assert().isEqualTo(404)
        // Review F-10: an ordinary binding, live or expired, is no break-glass binding: a 409 at once, nothing proposed.
        val before = fixture.ids.get()
        for (ordinary in listOf("no-delete", "contractor")) {
            val (refused, problem) =
                call("DELETE", "/break-glass/$ordinary", security = plato, query = mapOf("reason" to "done"))
            refused.assert().describedAs(ordinary).isEqualTo(409)
            problem.get("code").asString().assert().isEqualTo("CONFLICT")
            problem.get("detail").asString().assert().isEqualTo("binding [$ordinary] is not a break-glass binding")
        }
        fixture.ids.get().assert().isEqualTo(before)
    }

    /**
     * RC1-39: a 400's detail is a message of the control plane's own checks, or names the field that does not parse:
     * never an exception's message or a class name. A time that does not parse is the caller's 400, not a 500.
     */
    @Test
    fun `a field that does not parse is a 400 that names it, never an exception's message`() {
        fun refused(response: Pair<Int, JsonNode>, field: String) {
            response.first.assert().describedAs(field).isEqualTo(400)
            response.second.get("detail").asString().assert().describedAs(field).contains(field)
                .doesNotContain("me.ahoo").doesNotContain("Exception").doesNotContain("No enum constant")
                .doesNotContain("For input string").doesNotContain("could not be parsed")
        }
        val desired = """{"schema":2,"revision":2,"publishedAt":"2026-11-01T00:00:00Z"}"""
        val apply = call("POST", "/documents/tenant:acme:apply", """{"document":$desired,"mode":"BOGUS"}""")
        refused(apply, "[mode]")
        apply.second.get("detail").asString().assert().contains("MERGE").doesNotContain("ApplyMode")
        refused(
            call(
                "POST",
                "/break-glass",
                """{"principal":"plato","expiresAt":"tomorrow","reason":"incident"}""",
                security = plato
            ),
            "[expiresAt]"
        )
        val request = """{"tenant":"acme","principal":"bob","action":"order:read","resource":"order/1","time":"now"}"""
        refused(call("POST", "/simulate", """{"request":$request}"""), "[time]")
        refused(call("GET", "/documents/tenant:acme/revisions", query = mapOf("limit" to "many")), "[limit]")
        refused(call("GET", "/documents/tenant:acme/revisions", query = mapOf("before" to "x")), "[before]")
        refused(call("GET", "/documents/tenant:acme/revisions/first"), "[revision]")
        refused(call("GET", "/documents/tenant:acme/diff", query = mapOf("from" to "1", "to" to "x")), "[to]")
        val statement = """{"policy":"p","statement":${Long.MAX_VALUE},"digest":"d"}"""
        val proposal = """{"document":"tenant:acme","base":1,"operations":[],"confirmations":[$statement]}"""
        refused(call("POST", "/changes", proposal), "[confirmations]")
        // The parser's own message is not the control plane's either.
        val (malformed, problem) = call("POST", "/changes", "{not json")
        malformed.assert().isEqualTo(400)
        problem.get("detail").asString().assert().doesNotContain("Unexpected character").doesNotContain("code 110")
        // What the control plane's own checks say, it still says.
        call("POST", "/changes/c1:reject", """{"reason":" "}""").second.get("detail").asString()
            .assert().isEqualTo("A rejection needs a reason.")
    }

    /** Requests whose fields do not parse, each with what its 400's detail holds. */
    private val malformed: List<Case> = run {
        val request = """"tenant":"acme","principal":"bob","action":"order:read","resource":"order/1""""
        val proposal = """"document":"tenant:acme","base":1"""
        // Method, path, body, and what the detail holds.
        listOf(
            Case("POST", "/changes", """{$proposal,"operations":{}}""", "[operations] is not an array"),
            Case(
                "POST",
                "/changes",
                """{$proposal,"operations":[],"confirmations":{}}""",
                "[confirmations] is not an array",
            ),
            Case(
                "POST",
                "/changes",
                """{"document":"tenant:acme","base":"1","operations":[]}""",
                "[base] is not an integer",
            ),
            Case("POST", "/changes", """{"document":"nowhere","base":1,"operations":[]}""", "names no document"),
            Case("POST", "/changes", """{$proposal,"operations":[{"op":"explode"}]}""", "[operations]: "),
            Case(
                "POST",
                "/documents/tenant:acme:apply",
                """{"document":{"schema":1},"mode":"MERGE"}""",
                "[document]: ",
            ),
            Case("POST", "/documents/tenant:acme:rollback", """{"revision":"one"}""", "[revision] is not an integer"),
            Case(
                "POST",
                "/documents/tenant:acme:rollback",
                """{"revision":1,"confirmations":"x"}""",
                "[confirmations] is not an array",
            ),
            Case("POST", "/changes/a b:publish", null, "[change] is not a change id"),
            Case(
                "POST",
                "/changes/c1:publish",
                """{"ack":{"window":"w","counts":{"NARROWED":"x"}}}""",
                "[counts] is not an integer",
            ),
            Case("POST", "/changes/c1:publish", """{"ack":{"window":"w","counts":[]}}""", "[counts] is not an object"),
            Case(
                "POST",
                "/changes/c1:publish",
                """{"ack":{"window":"w","counts":{},"x":1}}""",
                "Unknown fields [x] in [ack]",
            ),
            Case("POST", "/break-glass/c1:approve", "{}", "[candidate] is missing", plato),
            Case(
                "POST",
                "/break-glass",
                """{"principal":"plato","expiresAt":"2026-11-01T01:00:00Z","reason":" "}""",
                "Break-glass needs a reason.",
                plato,
            ),
            Case(
                "DELETE",
                "/break-glass/glass",
                null,
                "Revoking break-glass needs a reason.",
                plato,
                mapOf("reason" to " "),
            ),
            Case("POST", "/simulate", """{"request":{$request,"roles":"admin"}}""", "[roles] is not an array"),
            Case("POST", "/simulate", """{"request":{$request,"roles":[1]}}""", "[roles] is not a string"),
            Case("POST", "/simulate", """{"request":{$request,"headers":[]}}""", "[headers] is not an object"),
            Case("POST", "/simulate", """{"request":{$request,"headers":{"x-a":[1]}}}""", "[headers] is not a string"),
            Case(
                "POST",
                "/simulate",
                """{"request":{$request,"requestAttributes":{"a":1}}}""",
                "[requestAttributes] is not a string",
            ),
            Case("POST", "/simulate", """{"request":{$request,"time":1}}""", "[time] is not a string"),
            Case("POST", "/simulate", """{"request":{$request,"time":"$FAR"}}""", "[time] is not an ISO-8601 instant"),
            Case("POST", "/explain", """{"request":{$request,"time":"$FAR"}}""", "[time] is not an ISO-8601 instant"),
            Case(
                "POST",
                "/break-glass",
                """{"principal":"plato","expiresAt":"$FAR","reason":"incident"}""",
                "[expiresAt] is not an ISO-8601 instant",
                plato,
            ),
            Case("POST", "/simulate", """{"request":{$request},"candidate":"a b"}""", "[candidate] is not a change id"),
            Case("POST", "/simulate", """{"request":[]}""", "[request] is not an object"),
            Case(
                "POST",
                "/explain",
                """{"request":{$request},"at":{"platform":"1"}}""",
                "[platform] is not an integer",
            ),
            Case(
                "POST",
                "/explain",
                """{"request":{$request},"at":{"platform":1,"x":1}}""",
                "Unknown fields [x] in [at]",
            ),
            Case("POST", "/analyze", """{"query":{"tenant":"acme","resource":"order/1"}}""", "[action] is missing"),
        )
    }

    /**
     * RC1-39, `CTL-24`: every field of every route is read by the control plane's own checks, so what does not parse is
     * a 400 that names it, and what is the control plane's own check says so in its own words.
     */
    @Test
    fun `every malformed field is named in the control plane's own words`() {
        for (case in malformed) {
            val (status, problem) = call(case.method, case.path, case.body, case.security ?: alice, case.query)
            status.assert().describedAs("${case.method} ${case.path} ${case.body}").isEqualTo(400)
            problem.get("detail").asString().assert().describedAs("${case.method} ${case.path}").contains(case.detail)
        }
    }

    private companion object {
        /** An instant `Instant.parse` reads and epoch milliseconds cannot hold. */
        const val FAR = "+300000000-01-01T00:00:00Z"
    }

    /** A request and what its 400's detail holds; by alice unless [security] says otherwise. */
    private class Case(
        val method: String,
        val path: String,
        val body: String?,
        val detail: String,
        val security: RequestSecurity? = null,
        val query: Map<String, String> = emptyMap(),
    )

    /**
     * RC1-39: a stored document that does not decode, perhaps one a newer version wrote during a rolling upgrade, is no
     * fault of the caller's: a 500 without detail, and the error is logged with its key. Before, it was a 400 that showed
     * the stored document's JSON path, also of an app document simulate read for a caller who may not read it.
     */
    @Test
    fun `a stored document that does not decode is a 500 without detail, logged with its key`() {
        runBlocking { fixture.bootstrap(SnapshotKey.App("order"), emptyList(), emptyList()) }
        fixture.undecodable += SnapshotKey.App("order")
        val logger = LoggerFactory.getLogger("me.ahoo.cosec.control") as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        try {
            val read = call("GET", "/documents/app:order", security = plato)
            val request = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order",""" +
                """"space":"team-a","action":"order:read","resource":"order/1"}"""
            val simulated = call("POST", "/simulate", """{"request":$request}""")
            for ((status, problem) in listOf(read, simulated)) {
                status.assert().isEqualTo(500)
                problem.get("code").asString().assert().isEqualTo("INTERNAL_ERROR")
                problem.has("detail").assert().isFalse()
            }
            logged.list.filter { it.level == Level.ERROR }.map { it.throwableProxy?.message.orEmpty() }
                .assert().hasSize(2).allMatch { it.contains("[app:order]") && it.contains("does not decode") }
        } finally {
            logger.detachAppender(logged)
        }
    }

    /**
     * Review L8 of #1240, `CTL-24`: a stored pattern this version cannot parse (a lone surrogate half, which a document
     * published before L4 of #1200 may hold) is a stored value `analyze` cannot read: a 500 logged with its key, never
     * the caller's 400.
     */
    @Test
    fun `a stored pattern this version cannot parse is a 500, logged with its key`() {
        runBlocking {
            fixture.bootstrap(
                SnapshotKey.Tenant("initech"),
                listOf(
                    me.ahoo.cosec.control.change.allow("broken", action = "order:read", resource = "http:path:/a\uD83D")
                ),
                listOf(
                    Binding(
                        BindingId("broken"),
                        SubjectRef.Anyone,
                        BindingScope(Match.Any, Match.Any),
                        PolicyId("broken")
                    )
                ),
            )
        }
        val logger = LoggerFactory.getLogger("me.ahoo.cosec.control") as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logged)
        try {
            val query = """{"query":{"tenant":"initech","action":"order:read","resource":"/a"}}"""
            val (status, problem) = call("POST", "/analyze", query, security = plato)
            status.assert().isEqualTo(500)
            problem.has("detail").assert().isFalse()
            logged.list.filter { it.level == Level.ERROR }.map { it.throwableProxy?.message.orEmpty() }
                .assert().hasSize(1).allMatch { it.contains("[tenant:initech]") && it.contains("cannot parse") }
        } finally {
            logger.detachAppender(logged)
        }
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
        // No such binding: a 404, and nothing proposed.
        call(
            "DELETE",
            "/break-glass/glass",
            security = plato,
            query = mapOf("reason" to "done")
        ).first.assert().isEqualTo(404)
        call("POST", "/break-glass/c9:approve", """{"candidate":"x"}""", security = plato).first.assert().isEqualTo(404)
    }
}
