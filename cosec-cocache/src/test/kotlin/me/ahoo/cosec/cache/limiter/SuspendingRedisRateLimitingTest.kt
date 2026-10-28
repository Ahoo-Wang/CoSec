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

package me.ahoo.cosec.cache.limiter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.coroutines.startCoroutine

/**
 * RFC 0006 §8.4: through the coroutine pipeline, the suspending Redis limiter is called on the event loop without
 * blocking it (`SnapshotStoreBlockHoundTest`), and the request goes on on `Dispatchers.Default` once Redis answers,
 * never on a Lettuce I/O thread.
 */
class SuspendingRedisRateLimitingTest {
    private val eventLoop = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-event-loop") }
    private val factory = ReactiveRedisRateLimiterTest.connectionFactory("localhost", 6379)

    @org.junit.jupiter.api.AfterEach
    fun close() {
        eventLoop.shutdownNow()
        factory.destroy()
    }

    private val platform = me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler().compile(
        me.ahoo.cosec.snapshot.SnapshotKey.Platform,
        me.ahoo.cosec.snapshot.SnapshotDocument(
            2,
            1,
            java.time.Instant.parse("2026-10-26T00:00:00Z"),
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z","policies":[""" +
                    """{"id":"open","statements":[{"effect":"allow","action":"*"}]}],""" +
                    """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"open"}],""" +
                    """"rateLimits":[{"id":"per-ip","action":"*","key":"environment.ip","permitsPerSecond":100}]}"""
                ).encodeToByteArray(),
        ),
    )

    private val views = object : me.ahoo.cosec.runtime.snapshot.SnapshotViews {
        override fun peek(key: me.ahoo.cosec.runtime.snapshot.ViewKey) = me.ahoo.cosec.engine.flat.SnapshotView(
            platform,
            null,
            key.tenant?.let { me.ahoo.cosec.engine.flat.CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: me.ahoo.cosec.runtime.snapshot.ViewKey) =
            java.util.concurrent.CompletableFuture.completedFuture(peek(key))
    }

    @Test
    fun theRequestGoesOnOnDefaultOnceRedisAnswers() {
        val limiter = ReactiveRedisRateLimiter(
            org.springframework.data.redis.core.ReactiveStringRedisTemplate(factory),
            "cosec:test:rate:${java.util.UUID.randomUUID()}",
        )
        val pipeline = me.ahoo.cosec.runtime.Pipeline(
            me.ahoo.cosec.runtime.PipelineComponents(
                { me.ahoo.cosec.runtime.authn.AuthnResult.Anonymous },
                me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource(views),
                rateLimiter = limiter,
            )
        )
        val facts = me.ahoo.cosec.runtime.http.HttpFacts(
            "GET",
            "/orders",
            null,
            me.ahoo.cosec.runtime.http.Headers.EMPTY,
            me.ahoo.cosec.runtime.http.Cookies.EMPTY,
            "10.0.0.1",
            me.ahoo.cosec.model.scope.HostScope(null),
        )
        val done = java.util.concurrent.CompletableFuture<Pair<me.ahoo.cosec.runtime.Outcome, Thread>>()
        eventLoop.execute {
            val call: suspend () -> me.ahoo.cosec.runtime.Outcome = { pipeline.evaluate(facts) }
            val context = kotlinx.coroutines.Dispatchers.Unconfined +
                me.ahoo.cosec.runtime.ResumeDispatcher(kotlinx.coroutines.Dispatchers.Default)
            call.startCoroutine(
                kotlin.coroutines.Continuation(context) { result ->
                    result.fold({ done.complete(it to Thread.currentThread()) }, done::completeExceptionally)
                }
            )
        }
        val (outcome, finishedOn) = done.get(10, java.util.concurrent.TimeUnit.SECONDS)
        outcome.status.assert().isEqualTo(200)
        outcome.headers["RateLimit-Limit"].assert().isEqualTo("100")
        // On the event loop when Redis answered before the await (nothing suspended, T1); else on Default (T2); never
        // on the Lettuce thread that completed the reply.
        (finishedOn.name == "test-event-loop" || finishedOn.name.startsWith("DefaultDispatcher-worker"))
            .assert().describedAs("finished on ${finishedOn.name}").isTrue()
    }
}
