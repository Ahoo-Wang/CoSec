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

import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Deterministic tests for [RedisSlidingWindowRateLimiter] driven by injected
 * timestamps, so the sliding-window interpolation math is verified exactly.
 *
 * Interpolation semantics: estimate = previousCount * weight + currentCount.
 * The weight applies ONLY to the previous window, so a window filled to its
 * quota rejects until the window rolls, and then decays linearly over the
 * next window.
 */
class RedisSlidingWindowRateLimiterTest {

    @Test
    fun initWhenPermitsPerSecondNotPositive() {
        assertThrows(IllegalArgumentException::class.java) {
            rateLimiter(permitsPerSecond = 0.0, windowSeconds = 5)
        }
    }

    @Test
    fun initWhenWindowSecondsBelowOne() {
        assertThrows(IllegalArgumentException::class.java) {
            rateLimiter(permitsPerSecond = 2.0, windowSeconds = 0)
        }
    }

    @Test
    fun weightDecayAllowsAfterWindowRoll() {
        // Quota per window: 2/s * 5s = 10.
        val limiter = rateLimiter(permitsPerSecond = 2.0, windowSeconds = 5)
        repeat(WINDOW_QUOTA) {
            limiter.tryAcquireInternal("t", 0L).assert().isTrue()
        }
        // Same window (t < 5000ms): currentCount = 10 -> estimate = 0*1 + 10 = 10 >= 10 -> rejected.
        assertThrows(TooManyRequestsException::class.java) {
            limiter.tryAcquireInternal("t", 0L)
        }
        // Window 1 at t=6000ms: previousCount = 10, weight = (5000-1000)/5000 = 0.8,
        // estimate = 10*0.8 + 0 = 8 < 10 -> allowed.
        limiter.tryAcquireInternal("t", 6_000L).assert().isTrue()
        // Same window at t=9000ms: weight = 0.2, estimate = 10*0.2 + 1 = 3 < 10 -> allowed.
        limiter.tryAcquireInternal("t", 9_000L).assert().isTrue()
        // Window 2 at t=10000ms: estimate = 0 -> allowed.
        limiter.tryAcquireInternal("t", 10_000L).assert().isTrue()
    }

    @Test
    fun rejectedRequestsAreNotCounted() {
        val limiter = rateLimiter(permitsPerSecond = 2.0, windowSeconds = 5)
        repeat(WINDOW_QUOTA) {
            limiter.tryAcquireInternal("t", 0L)
        }
        // Rejected three times. Had rejections counted, the window would hold 13;
        // at t=6000ms that would give estimate = 13*0.8 = 10.4 >= 10 -> rejected.
        // Since they do not count, estimate = 10*0.8 = 8 < 10 -> allowed.
        repeat(3) {
            assertThrows(TooManyRequestsException::class.java) {
                limiter.tryAcquireInternal("t", 0L)
            }
        }
        limiter.tryAcquireInternal("t", 6_000L).assert().isTrue()
    }

    @Test
    fun identicalConfigurationsShareQuota() {
        val prefix = UUID.randomUUID().toString()
        val limiterA = rateLimiter(permitsPerSecond = 2.0, windowSeconds = 5, prefix = prefix)
        val limiterB = rateLimiter(permitsPerSecond = 2.0, windowSeconds = 5, prefix = prefix)
        repeat(WINDOW_QUOTA / 2) {
            limiterA.tryAcquireInternal("t", 0L).assert().isTrue()
        }
        // B consumes the rest of the shared cluster-wide quota.
        repeat(WINDOW_QUOTA / 2) {
            limiterB.tryAcquireInternal("t", 0L).assert().isTrue()
        }
        assertThrows(TooManyRequestsException::class.java) {
            limiterA.tryAcquireInternal("t", 0L)
        }
        assertThrows(TooManyRequestsException::class.java) {
            limiterB.tryAcquireInternal("t", 0L)
        }
    }

    @Test
    fun differentConfigurationsAreIsolated() {
        val prefix = UUID.randomUUID().toString()
        val limiterA = rateLimiter(permitsPerSecond = 2.0, windowSeconds = 5, prefix = prefix)
        val limiterB = rateLimiter(permitsPerSecond = 3.0, windowSeconds = 5, prefix = prefix)
        repeat(WINDOW_QUOTA) {
            limiterA.tryAcquireInternal("t", 0L).assert().isTrue()
        }
        assertThrows(TooManyRequestsException::class.java) {
            limiterA.tryAcquireInternal("t", 0L)
        }
        // Different configuration -> different counters -> B's quota is untouched.
        limiterB.tryAcquireInternal("t", 0L).assert().isTrue()
    }

    @Test
    fun concurrentAcquisitionIsAtomic() {
        // Quota 1000 in one window; 2000 concurrent acquisitions share the same
        // injected timestamp, so the Lua script is the only arbiter.
        // windowSeconds=20 -> quota 1000 per window with a 40s key TTL. A 1s window (2s TTL)
        // could expire mid-burst on slow CI runners: the injected clock stays frozen at nowMs=0
        // while PEXPIRE ticks in real time, resetting the counter and opening a second window.
        val limiter = rateLimiter(permitsPerSecond = 50.0, windowSeconds = 20)
        val allowed = AtomicInteger()
        val rejected = AtomicInteger()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(THREADS)
        repeat(THREADS) {
            thread {
                startLatch.await()
                repeat(REQUESTS_PER_THREAD) {
                    try {
                        limiter.tryAcquireInternal("t", 0L)
                        allowed.incrementAndGet()
                    } catch (_: TooManyRequestsException) {
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

        private fun rateLimiter(
            permitsPerSecond: Double,
            windowSeconds: Long,
            prefix: String = UUID.randomUUID().toString(),
        ): RedisSlidingWindowRateLimiter =
            RedisSlidingWindowRateLimiter(
                stringRedisTemplate = stringRedisTemplate,
                keyPrefix = "test:rl:$prefix",
                permitsPerSecond = permitsPerSecond,
                windowSeconds = windowSeconds,
                strictFailure = false,
            )
    }
}
