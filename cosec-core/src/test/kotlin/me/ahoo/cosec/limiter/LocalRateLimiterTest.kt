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

package me.ahoo.cosec.limiter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Same scenarios as the Redis limiter test: both backends implement one sliding-window algorithm.
 */
class LocalRateLimiterTest {
    private var now = 0L
    private val limiter = LocalRateLimiter(clock = { now })

    /** Quota per window: 2/s * 5s = 10. */
    private val rateLimit = RateLimit(permitsPerSecond = 2.0, windowSeconds = 5)

    private fun acquire(bucket: String = "b", ruleId: String = "rule", at: Long = now): RateLimitResult {
        now = at
        return checkNotNull(limiter.tryAcquire(ruleId, bucket, rateLimit).block())
    }

    @Test
    fun weightDecayAllowsAfterWindowRoll() {
        repeat(WINDOW_QUOTA) {
            acquire(at = 0).allowed.assert().isTrue()
        }
        acquire(at = 0).allowed.assert().isFalse()
        // previous = 10 at weight 0.8 -> estimate 8 < 10.
        acquire(at = 6_000).allowed.assert().isTrue()
        // previous = 10 at weight 0.2 plus 1 current -> estimate 3 < 10.
        acquire(at = 9_000).allowed.assert().isTrue()
        // previous window holds 2 at weight 1.0 -> estimate 2 < 10.
        acquire(at = 10_000).allowed.assert().isTrue()
    }

    @Test
    fun windowsMoreThanOneApartReset() {
        repeat(WINDOW_QUOTA) {
            acquire(at = 0)
        }
        acquire(at = 0).allowed.assert().isFalse()
        acquire(at = 20_000).remaining.assert().isEqualTo(WINDOW_QUOTA - 1L)
    }

    @Test
    fun rejectedRequestsAreNotCounted() {
        repeat(WINDOW_QUOTA) {
            acquire(at = 0)
        }
        repeat(3) {
            acquire(at = 0).allowed.assert().isFalse()
        }
        acquire(at = 6_000).allowed.assert().isTrue()
    }

    @Test
    fun reportsRemainingAndReset() {
        val first = acquire(at = 1_000)
        first.limit.assert().isEqualTo(WINDOW_QUOTA.toLong())
        first.remaining.assert().isEqualTo(WINDOW_QUOTA - 1L)
        first.resetAfter.assert().isEqualTo(Duration.ofMillis(4_000))
    }

    @Test
    fun rulesAndBucketsAreIsolated() {
        repeat(WINDOW_QUOTA) {
            acquire(at = 0)
        }
        acquire(at = 0).allowed.assert().isFalse()
        acquire(ruleId = "other", at = 0).allowed.assert().isTrue()
        acquire(bucket = "other", at = 0).allowed.assert().isTrue()
    }

    @Test
    fun evictedBucketStartsFromZero() {
        val tinyLimiter = LocalRateLimiter(maximumBuckets = 1, clock = { 0 })
        repeat(WINDOW_QUOTA) {
            tinyLimiter.tryAcquire("rule", "a", rateLimit).block()
        }
        tinyLimiter.tryAcquire("rule", "b", rateLimit).block()
        tinyLimiter.tryAcquire("rule", "a", rateLimit)
            .test()
            .consumeNextWith { it.allowed.assert().isTrue() }
            .verifyComplete()
    }

    @Test
    fun concurrentAcquisitionNeverOvershoots() {
        val concurrentLimiter = LocalRateLimiter(clock = { 0 })
        val concurrentRateLimit = RateLimit(permitsPerSecond = 50.0, windowSeconds = 20)
        val allowed = AtomicInteger()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(THREADS)
        repeat(THREADS) {
            thread {
                startLatch.await()
                repeat(REQUESTS_PER_THREAD) {
                    if (checkNotNull(concurrentLimiter.tryAcquire("rule", "b", concurrentRateLimit).block()).allowed) {
                        allowed.incrementAndGet()
                    }
                }
                doneLatch.countDown()
            }
        }
        startLatch.countDown()
        doneLatch.await()
        allowed.get().assert().isEqualTo(CONCURRENT_QUOTA)
    }

    companion object {
        private const val WINDOW_QUOTA = 10
        private const val CONCURRENT_QUOTA = 1_000
        private const val THREADS = 10
        private const val REQUESTS_PER_THREAD = 200
    }
}
