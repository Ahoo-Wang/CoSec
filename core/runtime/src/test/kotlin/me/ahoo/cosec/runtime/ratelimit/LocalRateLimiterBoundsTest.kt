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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The local limiter's bounds and expiry, with real threads and the limiter's own clock: what it counts under contention,
 * what it admits under a flood, and what it removes, without a lock on the request path.
 */
class LocalRateLimiterBoundsTest {
    private val now = AtomicLong()
    private val perMinute = RateLimitSpec(1.0, 60)

    /** One permit per one-minute window. */
    private val once = RateLimitSpec(1.0 / 60, 60)

    private fun limiter(
        maximum: Long = 100_000,
        perRule: Long = maximum / 2,
        expiry: Duration = Duration.ofHours(1)
    ) =
        LocalRateLimiter(
            maximumBuckets = maximum,
            maximumBucketsPerRule = perRule,
            expireAfterAccess = expiry,
            clock = { now.get() }
        )

    private fun LocalRateLimiter.acquire(rule: String, bucket: String, limit: RateLimitSpec = perMinute) =
        runBlocking { tryAcquire(rule, bucket, limit) }

    /** Runs [task] on [threads] threads at once, [times] each, and waits for all of them. */
    private fun concurrently(threads: Int, times: Int, task: (thread: Int, iteration: Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val done = (0 until threads).map { thread ->
                pool.submit {
                    start.await()
                    repeat(times) { task(thread, it) }
                }
            }
            start.countDown()
            done.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** (a) Contention on one bucket loses no update: exactly the window's permits are granted. */
    @Test
    fun `one bucket under contention grants exactly its limit`() {
        val limiter = limiter()
        // 5 × 100 is exactly LIMIT: a quotient such as 500 / 60.0 × 60 rounds above it and grants one more.
        val limit = RateLimitSpec(5.0, 100)
        val allowed = AtomicInteger()
        concurrently(THREADS, PER_THREAD) { _, _ ->
            if (limiter.acquire("hot", "alice", limit).allowed) allowed.incrementAndGet()
        }
        allowed.get().assert().isEqualTo(LIMIT.toInt())
    }

    /** (b) Whatever admissions and sweeps interleave, the counters say what the map holds. */
    @Test
    fun `the counters equal what the limiter holds while sweeps run`() {
        val limiter = limiter(maximum = 400, perRule = 200, expiry = Duration.ofMillis(10))
        limiter.sweepChunk = SMALL_CHUNK
        val clockRuns = AtomicBoolean(true)
        val ticker = Thread {
            while (clockRuns.get()) {
                now.addAndGet(37)
                Thread.onSpinWait()
            }
        }.apply { start() }
        try {
            concurrently(THREADS, PER_THREAD) { thread, iteration ->
                try {
                    limiter.acquire("rule-${thread % 3}", "key-${iteration % 500}", RateLimitSpec(10.0, 1))
                } catch (_: LocalRateLimiter.LimiterFullException) {
                    // A full rule is a valid answer here; the counters are what is checked.
                }
            }
        } finally {
            clockRuns.set(false)
            ticker.join()
        }
        val (size, total, byRule) = limiter.held()
        total.assert().isEqualTo(size.toLong())
        byRule.values.sum().assert().isEqualTo(total)
        byRule.values.forEach { it.assert().isBetween(0L, 200L) }
        total.assert().isLessThanOrEqualTo(400L)
    }

    /** (c) A flood of distinct keys on rule B fills only B's share: A keeps counting and admits new buckets. */
    @Test
    fun `a flood on one rule leaves the other rules counting and admitting`() {
        val limiter = limiter(maximum = 100, perRule = 40)
        limiter.acquire("a", "alice", once).allowed.assert().isTrue()
        var refused = 0
        repeat(1_000) {
            try {
                limiter.acquire("b", "flood-$it")
            } catch (_: LocalRateLimiter.LimiterFullException) {
                refused++
            }
        }
        refused.assert().isEqualTo(960)
        limiter.acquire("a", "alice", once).allowed.assert().describedAs("alice's live count").isFalse()
        limiter.acquire("a", "bob", once).allowed.assert().describedAs("a new bucket of rule a").isTrue()
        limiter.held().third.assert().containsEntry("b", 40L).containsEntry("a", 2L)
    }

    /** (c) The total bound holds too: once all rules together are full, a new bucket of any rule cannot be counted. */
    @Test
    fun `a limiter full in total refuses new buckets of every rule`() {
        val limiter = limiter(maximum = 10, perRule = 5)
        repeat(5) { limiter.acquire("b", "b-$it", once) }
        repeat(5) { limiter.acquire("c", "c-$it") }
        assertThrownBy<LocalRateLimiter.LimiterFullException> { limiter.acquire("a", "alice") }
            .message().contains("rule [a]")
        limiter.acquire("b", "b-0", once).allowed.assert().describedAs("an existing bucket still counts").isFalse()
    }

    /** (d) A bucket idle for its expiry and two windows is removed, which frees its slot; one read meanwhile stays. */
    @Test
    fun `a bucket is removed only once it can no longer change an answer`() {
        val limiter = limiter(maximum = 2, perRule = 2, expiry = Duration.ofSeconds(10))
        limiter.acquire("r", "idle")
        limiter.acquire("r", "busy")
        now.set(100_000) // past the expiry, within two one-minute windows
        assertThrownBy<LocalRateLimiter.LimiterFullException> { limiter.acquire("r", "new") }
        limiter.acquire("r", "busy")
        // The idle bucket is now past its expiry and two windows; busy, read at 100s, is within two windows of it.
        now.set(150_000)
        limiter.acquire("r", "new").allowed.assert().isTrue()
        limiter.held().first.assert().describedAs("busy and new").isEqualTo(2)
    }

    /** A sweep cycle spans requests on different threads, a chunk each, and removes every bucket removable at its start. */
    @Test
    fun `a cycle spread over requests on several threads removes every removable bucket`() {
        val limiter = limiter(maximum = 10_000, expiry = Duration.ofSeconds(10))
        limiter.sweepChunk = SMALL_CHUNK
        repeat(STALE) { limiter.acquire("r", "stale-$it") }
        now.set(STALE_AFTER)
        val pool = Executors.newFixedThreadPool(4)
        try {
            var request = 0
            while (limiter.sweepCycles.get() < 2) {
                val probe = "probe-${request++}"
                pool.submit { limiter.acquire("p", probe) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            request.assert().describedAs("requests the cycle took").isGreaterThan(STALE / SMALL_CHUNK)
            val (_, _, byRule) = limiter.held()
            byRule.assert().containsEntry("r", 0L).containsEntry("p", request.toLong())
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * A full limiter starts no sweep before its interval is up, however many requests it refuses, and frees the slots of
     * removable buckets once a cycle is due.
     */
    @Test
    fun `a full limiter sweeps only when a cycle is due`() {
        val limiter = limiter(maximum = 10, perRule = 10, expiry = Duration.ofSeconds(10))
        repeat(10) { limiter.acquire("r", "k-$it") }
        val visits = limiter.sweepVisits.get()
        repeat(1_000) {
            assertThrownBy<LocalRateLimiter.LimiterFullException> { limiter.acquire("r", "flood-$it") }
        }
        limiter.sweepVisits.get().assert().describedAs("buckets visited by the flood").isEqualTo(visits)
        now.set(STALE_AFTER)
        limiter.acquire("r", "after").allowed.assert().isTrue()
        limiter.held().first.assert().isEqualTo(1)
    }

    /**
     * Two requests for one new key on a limiter with one slot: the one that loses the admission finds the other's bucket
     * and counts into it, never answers "full" for a key that has a bucket.
     */
    @Test
    fun `a request that loses the admission of its key counts into the winner's bucket`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(RACES) { race ->
                val limiter = limiter(maximum = 1, perRule = 1)
                val start = CyclicBarrier(2)
                val both = List(2) {
                    pool.submit<Boolean> {
                        start.await()
                        limiter.acquire("r", "k-$race", RateLimitSpec(10.0, 1)).allowed
                    }
                }
                both.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).assert().isTrue() }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** (e) A bucket a sweep removes while another thread holds it is never counted into: the thread finds its successor. */
    @Test
    fun `a bucket removed while held is not counted into`() {
        val limiter = limiter(maximum = 1_000, expiry = Duration.ofMillis(1))
        limiter.sweepChunk = SMALL_CHUNK
        repeat(ROUNDS) { round ->
            now.set(round * TEN_MINUTES)
            val key = "k-$round"
            concurrently(THREADS, 50) { _, _ -> limiter.acquire("r", key, RateLimitSpec(1_000.0, 60)) }
            // Every bucket of the previous rounds is idle for two windows now: the next round's sweep removes them while
            // this round's threads may still hold this one; the counters stay what the map holds.
            val (size, total, _) = limiter.held()
            total.assert().isEqualTo(size.toLong())
        }
    }

    private companion object {
        const val THREADS = 16
        const val PER_THREAD = 10_000
        const val LIMIT = 500L
        const val ROUNDS = 20
        const val TEN_MINUTES = 600_000L
        const val SMALL_CHUNK = 7
        const val STALE = 200

        /** Past a ten-second expiry and two one-minute windows. */
        const val STALE_AFTER = 121_000L
        const val RACES = 20_000
        const val TIMEOUT_SECONDS = 60L
    }
}
