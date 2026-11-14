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

import io.lettuce.core.cluster.SlotHash
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisClusterConfiguration
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * RFC 0006 §8.4: the suspending Redis limiter counts atomically in Redis, against real Redis, one key per bucket, by
 * Redis's clock (`OBL-4`); a failure is thrown. That it never blocks its caller
 * is in `SnapshotStoreBlockHoundTest`.
 */
class ReactiveRedisRateLimiterTest {
    private val factory = connectionFactory("localhost", 6379)
    private val prefix = "cosec:test:rate:${UUID.randomUUID()}"

    @AfterEach
    fun close() {
        factory.destroy()
    }

    private val redis = StringRedisTemplate(factory)

    /** Redis's clock, as the script reads it: `TIME`, in milliseconds. */
    private fun redisNow(): Long {
        val time = redis.execute(RedisCallback { it.serverCommands().time(TimeUnit.MILLISECONDS) })
        return checkNotNull(time)
    }

    /** OBL-4: a bucket is one key, a hash of its window and counts; what is counted there is spent. */
    @Test
    fun aBucketIsOneKeyThatEveryCountGivesAnExpiry() = runBlocking<Unit> {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix)
        val limit = quotaOf(2)
        limiter.tryAcquire("rule", "global", limit).remaining.assert().isEqualTo(1)
        val key = "$prefix:rule:global"
        redis.opsForHash<String, String>().get(key, "c").assert().isEqualTo("1")
        redis.keys("$prefix:*").orEmpty().assert().containsExactly(key)
        redis.opsForHash<String, String>().increment(key, "c", 1)
        val exhausted = limiter.tryAcquire("rule", "global", limit)
        exhausted.allowed.assert().isFalse()
        exhausted.remaining.assert().isZero()
        limiter.tryAcquire("rule", "k:other", limit).allowed.assert().isTrue()

        // M3: a key without an expiry, however it got one, has one again after the next count.
        redis.persist(key)
        redis.opsForHash<String, String>().put(key, "c", "0")
        limiter.tryAcquire("rule", "global", limit).allowed.assert().isTrue()
        val ttl = checkNotNull(redis.getExpire(key, TimeUnit.MILLISECONDS))
        ttl.assert().isPositive().isLessThanOrEqualTo(2 * RateLimiter.window(limit).windowMillis)
    }

    /** M3: the longest window is a day, so no counter outlives two days. */
    @Test
    fun aCounterOfTheLongestWindowExpiresWithinTwoDays() = runBlocking<Unit> {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix)
        limiter.tryAcquire(
            "rule",
            "k:v",
            RateLimitSpec(1.0, RateLimitSpec.MAX_WINDOW_SECONDS)
        ).allowed.assert().isTrue()
        checkNotNull(redis.getExpire("$prefix:rule:k:v", TimeUnit.SECONDS))
            .assert().isPositive().isLessThanOrEqualTo(2 * RateLimitSpec.MAX_WINDOW_SECONDS)
    }

    /**
     * OBL-4, L1: the window is Redis's, not the caller's: the count of the window before moves to the previous window's
     * place, an older one is dropped, and a window ahead of Redis's clock (a failover to a node behind) keeps counting.
     */
    @Test
    fun theWindowIsRedissNotTheCallers() = runBlocking<Unit> {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix)
        val limit = quotaOf(2)
        val window = redisNow() / RateLimiter.window(limit).windowMillis
        val hash = redis.opsForHash<String, String>()

        hash.putAll("$prefix:rule:before", mapOf("w" to "${window - 1}", "c" to "7", "p" to "0"))
        limiter.tryAcquire("rule", "before", limit)
        hash.entries("$prefix:rule:before").assert().containsEntry("p", "7")

        hash.putAll("$prefix:rule:old", mapOf("w" to "${window - 2}", "c" to "7", "p" to "7"))
        limiter.tryAcquire("rule", "old", limit).remaining.assert().isEqualTo(1)
        hash.entries("$prefix:rule:old").assert().containsEntry("p", "0").containsEntry("c", "1")

        hash.putAll("$prefix:rule:ahead", mapOf("w" to "${window + 5}", "c" to "1", "p" to "0"))
        limiter.tryAcquire("rule", "ahead", limit).allowed.assert().isTrue()
        hash.entries("$prefix:rule:ahead").assert().containsEntry("w", "${window + 5}").containsEntry("c", "2")
        limiter.tryAcquire("rule", "ahead", limit).allowed.assert().isFalse()
    }

    @Test
    fun aRedisThatCannotBeReachedIsThrown() {
        val unreachable = connectionFactory("localhost", 1)
        try {
            val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(unreachable), prefix)
            assertThrows<Exception> { runBlocking { limiter.tryAcquire("rule", "global", RateLimitSpec(1.0)) } }
        } finally {
            unreachable.destroy()
        }
    }

    /** OBL-4: different buckets are different keys, which spread over a cluster's slots. */
    @Test
    fun bucketsSpreadOverTheCluster() {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix)
        val slots = (0 until 64).map { SlotHash.getSlot(limiter.keyOf("rule", "user-$it")) }.toSet()
        slots.size.assert().isGreaterThan(32)
    }

    /**
     * Opt-in, as the snapshot tests' cluster target: `COSEC_TEST_REDIS_CLUSTER_NODES=127.0.0.1:7101,...` names the nodes
     * of a cluster with every slot assigned. Buckets of a platform, an app and a tenant rule, spread over its nodes.
     */
    @Test
    fun countsOnARedisCluster() {
        val nodes = System.getenv("COSEC_TEST_REDIS_CLUSTER_NODES")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        assumeTrue(!nodes.isNullOrEmpty()) { "Set COSEC_TEST_REDIS_CLUSTER_NODES to run this case." }
        val cluster = LettuceConnectionFactory(RedisClusterConfiguration(checkNotNull(nodes))).apply {
            afterPropertiesSet()
            start()
        }
        try {
            val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(cluster), prefix)
            val limit = quotaOf(2)
            runBlocking {
                for (rule in listOf("platform/rule", "app:shop/quota", "tenant:acme/quota")) {
                    (0 until 16).forEach { bucket ->
                        limiter.tryAcquire(rule, "k:b$bucket", limit).remaining.assert().isEqualTo(1)
                        limiter.tryAcquire(rule, "k:b$bucket", limit).remaining.assert().isZero()
                        limiter.tryAcquire(rule, "k:b$bucket", limit).allowed.assert().isFalse()
                    }
                }
            }
        } finally {
            cluster.destroy()
        }
    }

    companion object {
        /** A power of two, so that `permits / seconds * seconds` is exactly the permits; a window that outlives a test. */
        private const val WINDOW_SECONDS = 4_096L

        /**
         * [permits] for one window that outlives any test. A short one would not: a runner that stalls into the next
         * window would find the permits back.
         */
        fun quotaOf(permits: Int): RateLimitSpec = RateLimitSpec(permits.toDouble() / WINDOW_SECONDS, WINDOW_SECONDS)

        fun connectionFactory(host: String, port: Int): LettuceConnectionFactory =
            LettuceConnectionFactory(RedisStandaloneConfiguration(host, port)).apply {
                afterPropertiesSet()
                start()
            }
    }
}
