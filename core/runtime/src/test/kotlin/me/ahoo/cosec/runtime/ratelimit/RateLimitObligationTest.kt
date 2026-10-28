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

package me.ahoo.cosec.runtime.ratelimit

import me.ahoo.cosec.spi.ratelimit.RateLimiter

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.Basis
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.Unavailability
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * RFC 0006 §8.3, as 7.x `RateLimitingAuthorization` behaves: obligations run in order, the first exhausted rule is a
 * 429 with `Retry-After`, a permit reports the tightest rule's `RateLimit-*` headers, a strict rule that cannot be
 * counted is a 503 and any other is skipped, and a denied request spends nothing (P15).
 */
class RateLimitObligationTest {
    private fun platform(rules: String): CompiledDocument = PolicyDocumentCompiler().compile(
        SnapshotKey.Platform,
        SnapshotDocument(
            2,
            1,
            Instant.parse("2026-10-26T00:00:00Z"),
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z","policies":[""" +
                    """{"id":"read","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/orders/**"}]},""" +
                    """{"id":"no-delete","statements":[{"effect":"deny","action":"http:DELETE","resource":"http:path:/orders/**"}]}],""" +
                    """"bindings":[{"id":"g:read","subject":"*","app":"*","space":"*","policy":"read"},""" +
                    """{"id":"g:no-delete","subject":"*","app":"*","space":"*","policy":"no-delete"}],""" +
                    """"rateLimits":[$rules]}"""
                ).encodeToByteArray(),
        ),
    )

    private class Views(val platform: CompiledDocument) : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            platform,
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    /** Records which rules it was asked about, then answers as [delegate]. */
    private class Recording(private val delegate: RateLimiter) : RateLimiter {
        val asked = CopyOnWriteArrayList<String>()

        override suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult {
            asked += "$ruleId/$bucket"
            return delegate.tryAcquire(ruleId, bucket, limit)
        }
    }

    private val now = Instant.parse("2026-10-26T00:00:00.250Z").toEpochMilli()
    private val local = LocalRateLimiter(clock = { now })

    private fun evaluate(
        rules: String,
        limiter: RateLimiter? = local,
        method: String = "GET",
        times: Int = 1,
    ): List<Outcome> {
        val pipeline = PipelineFixtures.pipeline(
            authenticator = PipelineFixtures.authenticator(PipelineFixtures.authenticated()),
            engine = CoSecFlatEngine,
            views = Views(platform(rules)),
        ) { it.copy(options = PipelineOptions(rateLimitTimeout = 50.milliseconds), rateLimiter = limiter) }
        val facts = PipelineFixtures.facts("/orders/1").let {
            me.ahoo.cosec.runtime.http.HttpFacts(
                method,
                it.rawPath,
                it.rawQuery,
                it.headers,
                it.cookies,
                it.remoteAddress,
                it.host
            )
        }
        return List(times) { runBlocking { pipeline.evaluate(facts) } }
    }

    private val Outcome.cause: Cause get() = ((basis as Basis.Unavailable).reason as Unavailability.Indeterminate).cause

    private val twoPerSecond = """{"id":"two","action":"*","permitsPerSecond":2}"""

    @Test
    fun anExhaustedRuleIs429WithRetryAfter() {
        val (first, second, third) = evaluate(twoPerSecond, times = 3)
        first.status.assert().isEqualTo(200)
        first.headers["RateLimit-Limit"].assert().isEqualTo("2")
        first.headers["RateLimit-Remaining"].assert().isEqualTo("1")
        first.headers["Retry-After"].assert().isNull()
        second.headers["RateLimit-Remaining"].assert().isEqualTo("0")
        third.status.assert().isEqualTo(429)
        third.problem!!.type.slug.assert().isEqualTo("too-many-requests")
        third.headers["Retry-After"].assert().isEqualTo("1")
        third.headers["RateLimit-Remaining"].assert().isEqualTo("0")
        (third.basis as Basis.RateLimited).ruleId.assert().isEqualTo("two")
    }

    /** P15: a denied request never reaches a rate limiter. */
    @Test
    fun aDeniedRequestSpendsNothing() {
        val recording = Recording(local)
        evaluate(twoPerSecond, recording, method = "DELETE", times = 5).forEach { it.status.assert().isEqualTo(403) }
        recording.asked.assert().isEmpty()
        evaluate(twoPerSecond, recording).single().status.assert().isEqualTo(200)
    }

    @Test
    fun theFirstExhaustedRuleStopsTheRestAndAPermitReportsTheTightest() {
        val rules = """$twoPerSecond,{"id":"many","action":"*","key":"subject.id","permitsPerSecond":100}"""
        val recording = Recording(local)
        val outcomes = evaluate(rules, recording, times = 3)
        outcomes.map { it.status }.assert().containsExactly(200, 200, 429)
        // The tightest: two has 1 then 0 left, many 99 then 98.
        outcomes[0].headers["RateLimit-Limit"].assert().isEqualTo("2")
        recording.asked.assert().containsExactly(
            "two/global",
            "many/g:alice",
            "two/global",
            "many/g:alice",
            "two/global"
        )
    }

    @Test
    fun aStrictRuleThatCannotBeCountedIs503AndALenientOneIsSkipped() {
        val failing = RateLimiter { _, _, _ -> error("Redis is down") }
        val strict = """{"id":"strict","action":"*","permitsPerSecond":2,"strictFailure":true}"""
        val outcome = evaluate(strict, failing).single()
        outcome.status.assert().isEqualTo(503)
        outcome.cause.assert().isEqualTo(Cause.RateLimiterUnavailable("strict"))
        val lenient = evaluate(twoPerSecond, failing).single()
        lenient.status.assert().isEqualTo(200)
        lenient.headers["RateLimit-Limit"].assert().isNull()
        // A limiter that does not answer in time, and no limiter at all, are the same.
        val hanging = RateLimiter { _, _, _ -> awaitCancellation() }
        evaluate(strict, hanging).single().status.assert().isEqualTo(503)
        evaluate(strict, null).single().status.assert().isEqualTo(503)
        evaluate(twoPerSecond, null).single().status.assert().isEqualTo(200)
    }

    @Test
    fun theLocalLimiterSlidesItsWindow() = runBlocking<Unit> {
        var clock = 0L
        val limiter = LocalRateLimiter(clock = { clock })
        val limit = RateLimitSpec(1.0, 1)
        limiter.tryAcquire("r", "b", limit).allowed.assert().isTrue()
        limiter.tryAcquire("r", "b", limit).allowed.assert().isFalse()
        clock = 1_999
        limiter.tryAcquire("r", "b", limit).allowed.assert().isTrue()
        limiter.tryAcquire("r", "other", limit).resetAfter.assert().isEqualTo(Duration.ofMillis(1))
    }
}
