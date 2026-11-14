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
 * bucket's two windows on one Redis Cluster slot (`OBL-4`, #1229); a failure is thrown. That it never blocks its caller
 * is in `SnapshotStoreBlockHoundTest`.
 */
class ReactiveRedisRateLimiterTest {
    private val factory = connectionFactory("localhost", 6379)
    private val prefix = "cosec:test:rate:${UUID.randomUUID()}"

    @AfterEach
    fun close() {
        factory.destroy()
    }

    @Test
    fun countsUnderTheBucketsHashTag() = runBlocking<Unit> {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix) { FIXED_NOW }
        val limit = quotaOf(2)
        limiter.tryAcquire("rule", "global", limit).remaining.assert().isEqualTo(1)
        // `<keyPrefix>:{<ruleId>:<bucket>}:<windowIndex>`: what is counted there is spent.
        val key = "$prefix:{rule:global}:${FIXED_NOW / RateLimiter.window(limit).windowMillis}"
        val redis = StringRedisTemplate(factory)
        redis.opsForValue().get(key).assert().isEqualTo("1")
        redis.opsForValue().increment(key)
        val exhausted = limiter.tryAcquire("rule", "global", limit)
        exhausted.allowed.assert().isFalse()
        exhausted.remaining.assert().isZero()
        limiter.tryAcquire("rule", "k:other", limit).allowed.assert().isTrue()
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
        val prefixes = listOf("cosec:rate-limiter", "{cosec}:rate-limiter", "a{b:rate-limiter", "a}b{c}:rl", "x{", "")
        val rules =
            listOf("platform/rule", "platform/a.b_c-d", "app:shop/quota", "tenant:acme/eu/quota", "tenant:}x/quota", "")
        fun text(length: Int) = (0 until length).map { BUCKET_CHARACTERS.random(random) }.joinToString("")

        // A random prefix, unless it is one the limiter refuses: its first `{` immediately followed by `}`.
        fun prefix(): String = generateSequence { text(random.nextInt(0, 10)) }.first { candidate ->
            val trimmed = candidate.trimEnd(':')
            val brace = trimmed.indexOf('{')
            brace < 0 || trimmed.getOrNull(brace + 1) != '}'
        }
        val template = ReactiveStringRedisTemplate(factory)
        repeat(10_000) {
            val limiter =
                ReactiveRedisRateLimiter(template, if (random.nextBoolean()) prefixes.random(random) else prefix())
            // The engine's rule ids, or anything that does not begin with `}`.
            val rule = if (random.nextBoolean()) rules.random(random) else text(random.nextInt(0, 6)).trimStart('}')
            val (current, previous) = limiter.keysOf(rule, text(random.nextInt(0, 8)), random.nextLong(1, 1L shl 40))
            SlotHash.getSlot(current).assert().describedAs("$current, $previous").isEqualTo(SlotHash.getSlot(previous))
        }
    }

    @Test
    fun aRuleIdThatWouldEmptyTheHashTagIsRefused() {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix)
        assertThrows<IllegalArgumentException> { limiter.keysOf("}rule", "global", 1) }
        val refused = runBlocking { runCatching { limiter.tryAcquire("}rule", "global", RateLimitSpec(1.0)) } }
        refused.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
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
            val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(cluster), prefix) { FIXED_NOW }
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
        /** Braces, separators, a non-ASCII letter and both halves of a surrogate pair, alone or together. */
        private const val BUCKET_CHARACTERS = "ab{}:/ \u00e9\ud83d\ude00"

        /** A power of two, so that `permits / seconds * seconds` is exactly the permits. */
        private const val WINDOW_SECONDS = 4_096L

        /** The clock of a test that counts: any instant, the same for every call, so that they share one window. */
        const val FIXED_NOW = 1_700_000_000_250L

        /**
         * [permits] for one window that outlives any test. A short one would not: the script's TTL on a counter is real
         * time, two windows whatever the limiter's clock says, so a runner that stalls for two seconds would lose the
         * counter even at [FIXED_NOW], and the permits would be back.
         */
        fun quotaOf(permits: Int): RateLimitSpec = RateLimitSpec(permits.toDouble() / WINDOW_SECONDS, WINDOW_SECONDS)

        fun connectionFactory(host: String, port: Int): LettuceConnectionFactory =
            LettuceConnectionFactory(RedisStandaloneConfiguration(host, port)).apply {
                afterPropertiesSet()
                start()
            }
    }
}
