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
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.random.Random

/**
 * RFC 0006 §8.4: the suspending Redis limiter counts atomically in Redis, against real Redis, in keys that keep a
 * bucket's two windows on one Redis Cluster slot (#1229); a failure is thrown. That it never blocks its caller is in `SnapshotStoreBlockHoundTest`.
 */
class ReactiveRedisRateLimiterTest {
    private val factory = connectionFactory("localhost", 6379)
    private val prefix = "cosec:test:rate:${UUID.randomUUID()}"
    private val clock = 1_700_000_000_250L

    @AfterEach
    fun close() {
        factory.destroy()
    }

    @Test
    fun countsUnderTheBucketsHashTag() = runBlocking<Unit> {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix) { clock }
        val limit = RateLimitSpec(2.0)
        limiter.tryAcquire("rule", "global", limit).remaining.assert().isEqualTo(1)
        // `<keyPrefix>:{<ruleId>:<bucket>}:<windowIndex>`: what is counted there is spent.
        val key = "$prefix:{rule:global}:${clock / RateLimiter.window(limit).windowMillis}"
        val redis = StringRedisTemplate(factory)
        redis.opsForValue().get(key).assert().isEqualTo("1")
        redis.opsForValue().increment(key)
        val exhausted = limiter.tryAcquire("rule", "global", limit)
        exhausted.allowed.assert().isFalse()
        exhausted.remaining.assert().isZero()
        limiter.tryAcquire("rule", "g:other", limit).allowed.assert().isTrue()
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

    @Test
    fun aBucketsTwoWindowsShareASlotWhateverThePrefixRuleAndBucketHold() {
        val random = Random(1229)
        val prefixes = listOf("cosec:rate-limiter", "{cosec}:rate-limiter", "a{b:rate-limiter", "a}b{c}:rl", "x{")
        val rules = listOf("rule", "a.b_c-d", "app:shop/quota", "tenant:acme/eu/quota", "tenant:}x/quota")
        repeat(2_000) {
            val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefixes.random(random))
            val bucket = (0 until random.nextInt(0, 8)).map { BUCKET_CHARACTERS.random(random) }.joinToString("")
            val (current, previous) = limiter.keysOf(rules.random(random), bucket, random.nextLong(1, 1L shl 40))
            SlotHash.getSlot(current).assert().isEqualTo(SlotHash.getSlot(previous))
        }
    }

    @Test
    fun bucketsSpreadOverTheCluster() {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix)
        val slots = (0 until 64).map { SlotHash.getSlot(limiter.keysOf("rule", "user-$it", 1).first()) }.toSet()
        slots.size.assert().isGreaterThan(32)
    }

    @Test
    fun aPrefixWithAnEmptyHashTagIsRefused() {
        assertThrows<IllegalArgumentException> {
            ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), "cosec{}:rate-limiter")
        }
    }

    /** Opt-in, as the snapshot tests' cluster target: `COSEC_TEST_REDIS_CLUSTER_NODES=127.0.0.1:7000,...`. */
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
            val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(cluster), prefix) { clock }
            val limit = RateLimitSpec(2.0)
            runBlocking {
                (0 until 16).forEach { bucket ->
                    limiter.tryAcquire("rule", "b$bucket", limit).remaining.assert().isEqualTo(1)
                    limiter.tryAcquire("rule", "b$bucket", limit).remaining.assert().isZero()
                    limiter.tryAcquire("rule", "b$bucket", limit).allowed.assert().isFalse()
                }
            }
        } finally {
            cluster.destroy()
        }
    }

    companion object {
        /** Braces, separators, a non-ASCII letter and both halves of a surrogate pair, alone or together. */
        private const val BUCKET_CHARACTERS = "ab{}:/ \u00e9\ud83d\ude00"

        fun connectionFactory(host: String, port: Int): LettuceConnectionFactory =
            LettuceConnectionFactory(RedisStandaloneConfiguration(host, port)).apply {
                afterPropertiesSet()
                start()
            }
    }
}
