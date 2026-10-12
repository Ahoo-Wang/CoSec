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

import me.ahoo.cosec.limiter.RateLimit
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import reactor.kotlin.test.test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Deterministic tests for [RedisRateLimiter] driven by injected timestamps, so the sliding-window interpolation
 * math is verified exactly.
 *
 * Interpolation semantics: estimate = previousCount * weight + currentCount.
 * The weight applies ONLY to the previous window, so a window filled to its
 * quota rejects until the window rolls, and then decays linearly over the
 * next window.
 */
class RedisRateLimiterTest {

    /** Quota per window: 2/s * 5s = 10. */
    private val rateLimit = RateLimit(permitsPerSecond = 2.0, windowSeconds = 5)

    @Test
    fun weightDecayAllowsAfterWindowRoll() {
        val limiter = rateLimiter()
        repeat(WINDOW_QUOTA) {
            limiter.acquire("rule", "b", rateLimit, 0L).allowed.assert().isTrue()
        }
        // Same window (t < 5000ms): currentCount = 10 -> estimate = 0*1 + 10 = 10 >= 10 -> rejected.
        limiter.acquire("rule", "b", rateLimit, 0L).allowed.assert().isFalse()
        // Window 1 at t=6000ms: previousCount = 10, weight = (5000-1000)/5000 = 0.8,
        // estimate = 10*0.8 + 0 = 8 < 10 -> allowed.
        limiter.acquire("rule", "b", rateLimit, 6_000L).allowed.assert().isTrue()
        // Same window at t=9000ms: weight = 0.2, estimate = 10*0.2 + 1 = 3 < 10 -> allowed.
        limiter.acquire("rule", "b", rateLimit, 9_000L).allowed.assert().isTrue()
        // Window 2 at t=10000ms: previous window holds 2 acquires at weight 1.0,
        // estimate = 2 < 10 -> allowed.
        limiter.acquire("rule", "b", rateLimit, 10_000L).allowed.assert().isTrue()
    }

    @Test
    fun reportsRemainingAndReset() {
        val limiter = rateLimiter()
        val first = limiter.acquire("rule", "b", rateLimit, 1_000L)
        first.limit.assert().isEqualTo(WINDOW_QUOTA.toLong())
        first.remaining.assert().isEqualTo(WINDOW_QUOTA - 1L)
        first.resetAfter.assert().isEqualTo(Duration.ofMillis(4_000))
        repeat(WINDOW_QUOTA - 1) {
            limiter.acquire("rule", "b", rateLimit, 1_000L)
        }
        val rejected = limiter.acquire("rule", "b", rateLimit, 1_000L)
        rejected.allowed.assert().isFalse()
        rejected.remaining.assert().isZero()
    }

    @Test
    fun rejectedRequestsAreNotCounted() {
        val limiter = rateLimiter()
        repeat(WINDOW_QUOTA) {
            limiter.acquire("rule", "b", rateLimit, 0L)
        }
        // Rejected three times. Had rejections counted, the window would hold 13;
        // at t=6000ms that would give estimate = 13*0.8 = 10.4 >= 10 -> rejected.
        // Since they do not count, estimate = 10*0.8 = 8 < 10 -> allowed.
        repeat(3) {
            limiter.acquire("rule", "b", rateLimit, 0L).allowed.assert().isFalse()
        }
        limiter.acquire("rule", "b", rateLimit, 6_000L).allowed.assert().isTrue()
    }

    @Test
    fun rulesWithIdenticalLimitsAreIsolated() {
        val limiter = rateLimiter()
        repeat(WINDOW_QUOTA) {
            limiter.acquire("rule-a", "b", rateLimit, 0L).allowed.assert().isTrue()
        }
        limiter.acquire("rule-a", "b", rateLimit, 0L).allowed.assert().isFalse()
        // The quota belongs to the rule id, not to its settings.
        limiter.acquire("rule-b", "b", rateLimit, 0L).allowed.assert().isTrue()
    }

    @Test
    fun bucketsAreIsolated() {
        val limiter = rateLimiter()
        repeat(WINDOW_QUOTA) {
            limiter.acquire("rule", "g:1.1.1.1", rateLimit, 0L)
        }
        limiter.acquire("rule", "g:1.1.1.1", rateLimit, 0L).allowed.assert().isFalse()
        limiter.acquire("rule", "g:2.2.2.2", rateLimit, 0L).allowed.assert().isTrue()
    }

    @Test
    fun tryAcquire() {
        rateLimiter().tryAcquire("rule", "b", rateLimit)
            .test()
            .consumeNextWith { it.allowed.assert().isTrue() }
            .verifyComplete()
    }

    @Test
    fun concurrentAcquisitionIsAtomic() {
        // Quota 1000 in one window; 2000 concurrent acquisitions share the same
        // injected timestamp, so the Lua script is the only arbiter.
        // windowSeconds=20 -> quota 1000 per window with a 40s key TTL. A 1s window (2s TTL)
        // could expire mid-burst on slow CI runners: the injected clock stays frozen at nowMs=0
        // while PEXPIRE ticks in real time, resetting the counter and opening a second window.
        val limiter = rateLimiter()
        val concurrentRateLimit = RateLimit(permitsPerSecond = 50.0, windowSeconds = 20)
        val allowed = AtomicInteger()
        val rejected = AtomicInteger()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(THREADS)
        repeat(THREADS) {
            thread {
                startLatch.await()
                repeat(REQUESTS_PER_THREAD) {
                    if (limiter.acquire("rule", "b", concurrentRateLimit, 0L).allowed) {
                        allowed.incrementAndGet()
                    } else {
                        rejected.incrementAndGet()
                    }
                }
                doneLatch.countDown()
            }
        }
        startLatch.countDown()
        doneLatch.await()
        (allowed.get() + rejected.get()).assert().isEqualTo(THREADS * REQUESTS_PER_THREAD)
        // Atomic check-and-count must never overshoot the quota.
        allowed.get().assert().isLessThanOrEqualTo(CONCURRENT_QUOTA)
        rejected.get().assert().isGreaterThan(0)
    }

    companion object {
        private const val WINDOW_QUOTA = 10
        private const val CONCURRENT_QUOTA = 1_000
        private const val THREADS = 10
        private const val REQUESTS_PER_THREAD = 200

        private lateinit var stringRedisTemplate: StringRedisTemplate

        @BeforeAll
        @JvmStatic
        fun setup() {
            val connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
            connectionFactory.afterPropertiesSet()
            stringRedisTemplate = StringRedisTemplate(connectionFactory)
        }

        @AfterAll
        @JvmStatic
        fun destroy() {
            stringRedisTemplate.connectionFactory?.let { (it as LettuceConnectionFactory).destroy() }
        }

        private fun rateLimiter(): RedisRateLimiter =
            RedisRateLimiter(stringRedisTemplate, keyPrefix = "test:rl:${UUID.randomUUID()}")
    }
}
