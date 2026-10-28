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

package me.ahoo.cosec.e2e

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.ProblemEncoder
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.BlacklistKind
import me.ahoo.cosec.runtime.attribute.BlacklistSource
import me.ahoo.cosec.runtime.attribute.InMemoryBlacklistSource
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * The fixed requests of RFC 0008 §4.4.3, through the assembled 8.0 path against real Redis. Only what exists once the
 * parts are wired: the documents' real encoding in Redis, CoCache's reads, installs and revalidation, scope
 * resolution, the status mapping and the problem bodies. The rules themselves are the conformance suite's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SliceTest {
    private val slice = Slice()

    private val platform = """
        ,"policies":[{"id":"deny-delete","statements":[{"name":"no-delete","effect":"deny","action":"http:DELETE","resource":"http:path:/orders/**"}]},
                     {"id":"health","statements":[{"name":"health","effect":"allow","action":"http:GET","resource":"http:path:/health"}]}],
         "bindings":[{"id":"g:deny-delete","subject":"*","app":"*","space":"*","policy":"deny-delete"},
                     {"id":"g:health","subject":"*","app":"*","space":"*","policy":"health"}]
    """.trimIndent()

    private val app = """
        ,"catalog":{"groups":[{"name":"order","permissions":[{"id":"read"}]}]},
         "policies":[{"id":"perm:order/read","statements":[{"name":"read","effect":"allow","action":"http:GET","resource":"http:path:/orders/**"}]}],
         "bindings":[{"id":"r:clerk:read","subject":{"role":"clerk"},"app":"order","space":"*","policy":"perm:order/read"}]
    """.trimIndent()

    private val acme = """
        ,"policies":[{"id":"no-export","statements":[{"name":"export","effect":"deny","action":"http:GET","resource":"http:path:/orders/export"}]}],
         "bindings":[{"id":"t:no-export","subject":"*","app":"*","space":"*","policy":"no-export"}]
    """.trimIndent()

    /** Another tenant allows everything to everyone of its own. */
    private val beta = """
        ,"policies":[{"id":"anything","statements":[{"name":"anything","effect":"allow","action":"*"}]}],
         "bindings":[{"id":"t:anything","subject":"*","app":"*","space":"*","policy":"anything"}]
    """.trimIndent()

    @BeforeAll
    fun publish() {
        slice.publish(SnapshotKey.Platform, platform)
        slice.publish(SnapshotKey.App("order"), app)
        slice.publish(SnapshotKey.Tenant("acme"), acme)
        slice.publish(SnapshotKey.Tenant("beta"), beta)
        slice.publish(SnapshotKey.Tenant("broken"), ""","policies":[{"id":"perm:order/x","statements":[]}]""")
    }

    @AfterAll
    fun close() {
        slice.close()
    }

    private fun evaluate(method: String, path: String, token: String? = null, app: String? = "order", space: String? = null): Outcome =
        runBlocking { slice.evaluate(request(method, path, token, app, space)) }

    private fun Outcome.slug(): String? = problem?.type?.slug

    @Test
    fun `a role binding of the app document permits`() {
        val outcome = evaluate("GET", "/orders/1", "alice@acme:clerk")
        outcome.proceed.assert().isTrue()
        outcome.status.assert().isEqualTo(200)
        outcome.problem.assert().isNull()
    }

    @Test
    fun `a platform deny refuses, 401 for an anonymous caller`() {
        evaluate("DELETE", "/orders/1", "alice@acme:clerk").let {
            it.status.assert().isEqualTo(403)
            it.slug().assert().isEqualTo("forbidden")
        }
        evaluate("DELETE", "/orders/1").let {
            it.status.assert().isEqualTo(401)
            it.slug().assert().isEqualTo("unauthorized")
        }
        // A rejected token is evaluated as anonymous, and says why.
        evaluate("DELETE", "/orders/1", "invalid").slug().assert().isEqualTo("token-invalid")
        // An anonymous caller still gets what Anyone may do.
        evaluate("GET", "/health", app = null).proceed.assert().isTrue()
    }

    @Test
    fun `nothing that allows is an implicit deny, indistinguishable from an explicit one`() {
        val outcome = evaluate("GET", "/admin", "alice@acme:clerk")
        outcome.status.assert().isEqualTo(403)
        outcome.slug().assert().isEqualTo("forbidden")
    }

    @Test
    fun `a tenant's document decides for its own principals only`() {
        evaluate("GET", "/orders/export", "alice@acme:clerk").status.assert().isEqualTo(403)
        evaluate("GET", "/orders/export", "bob@beta:clerk").proceed.assert().isTrue()
        // beta allows everything, for beta's principals: never for acme's.
        evaluate("GET", "/admin", "alice@acme:clerk").status.assert().isEqualTo(403)
        evaluate("GET", "/admin", "bob@beta").proceed.assert().isTrue()
    }

    @Test
    fun `a space the principal has no role in is rejected`() {
        val outcome = evaluate("GET", "/orders/1", "alice@acme:clerk", space = "team-x")
        outcome.status.assert().isEqualTo(403)
        outcome.slug().assert().isEqualTo("scope-rejected")
    }

    @Test
    fun `a document that does not compile makes its requests unavailable`() {
        val outcome = evaluate("GET", "/orders/1", "carol@broken:clerk")
        outcome.status.assert().isEqualTo(503)
        outcome.slug().assert().isEqualTo("unavailable")
        // Another tenant is not affected.
        evaluate("GET", "/orders/1", "alice@acme:clerk").proceed.assert().isTrue()
    }

    /** RFC 0006 §5.4.4: the blacklist in stage A, and its failure, through the assembled path. */
    @Test
    fun `a blocked address is denied and a blacklist that fails is unavailable`() {
        val blacklist = InMemoryBlacklistSource()
        Slice(attributes = AttributeProviders(listOf(BlacklistAttributeProvider(blacklist)))).use { own ->
            own.publish(SnapshotKey.Platform, platform)
            own.publish(SnapshotKey.App("order"), app)
            own.publish(SnapshotKey.Tenant("acme"), acme)
            val read = request("GET", "/orders/1", "alice@acme:clerk", "order")
            runBlocking { own.evaluate(read) }.proceed.assert().isTrue()
            blacklist.block(BlacklistKind.IP, "10.0.0.1")
            runBlocking { own.evaluate(read) }.status.assert().isEqualTo(403)
        }
        val failing = BlacklistSource { _, _ -> error("The blacklist is down") }
        Slice(attributes = AttributeProviders(listOf(BlacklistAttributeProvider(failing)))).use { own ->
            own.publish(SnapshotKey.Platform, platform)
            own.publish(SnapshotKey.App("order"), app)
            own.publish(SnapshotKey.Tenant("acme"), acme)
            val outcome = runBlocking { own.evaluate(request("GET", "/orders/1", "alice@acme:clerk", "order")) }
            outcome.status.assert().isEqualTo(503)
            outcome.slug().assert().isEqualTo("unavailable")
            // A matching DENY still decides.
            runBlocking { own.evaluate(request("DELETE", "/orders/1", "alice@acme:clerk", "order")) }.status
                .assert().isEqualTo(403)
        }
    }

    @Test
    fun `problems are written as problem+json with the request id`() {
        val outcome = evaluate("DELETE", "/orders/1", "alice@acme:clerk")
        val body = CoSecJsonSerializer.readTree(ProblemEncoder.encode(outcome.problem!!))
        body.get("type").asString().assert().isEqualTo("https://cosec.ahoo.me/problems/forbidden")
        body.get("title").asString().assert().isEqualTo("Forbidden")
        body.get("status").asInt().assert().isEqualTo(403)
        body.get("requestId").asString().assert().isEqualTo(outcome.requestId)
        outcome.headers["CoSec-Request-Id"].assert().isEqualTo(outcome.requestId)
        body.has("policy").assert().isFalse()
    }

    /** #1053 review: once a platform document is required, its absence fails requests closed, never reads as empty. */
    @Test
    fun `without a platform document every request is unavailable`() {
        Slice().use { own ->
            own.publish(SnapshotKey.Tenant("acme"), acme)
            val outcome = runBlocking { own.evaluate(request("GET", "/orders/1", "alice@acme:clerk", "order")) }
            outcome.status.assert().isEqualTo(503)
            outcome.slug().assert().isEqualTo("unavailable")
        }
    }

    @Test
    fun `a new revision decides within the staleness bound`() {
        Slice().use { own ->
            own.publish(SnapshotKey.Platform, platform)
            own.publish(SnapshotKey.Tenant("acme"), acme)
            val export = { runBlocking { own.evaluate(request("GET", "/orders/export", "alice@acme")) } }
            export().status.assert().isEqualTo(403)
            own.publish(
                SnapshotKey.Tenant("acme"),
                ""","policies":[{"id":"export","statements":[{"effect":"allow","action":"*"}]}],"bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"export"}]"""
            )
            val deadline = System.nanoTime() + STALENESS_BOUND.toNanos()
            while (!export().proceed) {
                check(System.nanoTime() < deadline) { "The new revision was not seen within $STALENESS_BOUND." }
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    @Test
    fun `when Redis goes down, a document not yet loaded is unavailable, never permitted`() {
        RedisOutage().use { redis ->
            val options =
                SnapshotOptions(revalidateInterval = Duration.ofMillis(200), coldLoadTimeout = Duration.ofMillis(500))
            Slice(redisUri = redis.uri, options = options).use { down ->
                down.publish(SnapshotKey.Platform, platform)
                down.publish(SnapshotKey.App("order"), app)
                down.publish(SnapshotKey.Tenant("acme"), acme)
                val acmeRead = request("GET", "/orders/1", "alice@acme:clerk", "order")
                runBlocking { down.evaluate(acmeRead) }.proceed.assert().isTrue()

                redis.stop()
                val cold = runBlocking { down.evaluate(request("GET", "/orders/1", "bob@beta:clerk", "order")) }
                cold.status.assert().isEqualTo(503)
                cold.slug().assert().isEqualTo("unavailable")
                // Installed documents keep deciding as last confirmed (`failClosedTenants` bounds their lag per
                // tenant): an outage freezes them, it does not drop them, so a platform DENY still holds.
                runBlocking { down.evaluate(acmeRead) }.proceed.assert().isTrue()
                runBlocking { down.evaluate(request("DELETE", "/orders/1", "alice@acme:clerk", "order")) }
                    .status.assert().isEqualTo(403)
            }
        }
    }

    private companion object {
        /** Notification or the 200 ms revalidation: well inside a second; generous for CI. */
        val STALENESS_BOUND: Duration = Duration.ofSeconds(10)
        const val POLL_MILLIS = 50L
    }
}
