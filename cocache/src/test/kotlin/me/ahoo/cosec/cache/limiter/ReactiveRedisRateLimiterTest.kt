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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

/**
 * RFC 0006 §8.4: the suspending Redis limiter counts with the Lua script of the 7.x one, in the same keys, against real
 * Redis; a failure is thrown. That it never blocks its caller is in `SnapshotStoreBlockHoundTest`.
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
    fun countsInTheBucketsOfThe7xLimiter() = runBlocking<Unit> {
        val limiter = ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), prefix) { clock }
        val legacy = RedisRateLimiter(StringRedisTemplate(factory), prefix) { clock }
        val limit = RateLimitSpec(2.0)
        limiter.tryAcquire("rule", "global", limit).remaining.assert().isEqualTo(1)
        // The 7.x limiter on another instance spends from the same bucket during a rolling upgrade.
        legacy.acquire("rule", "global", RedisRateLimiterTestLimits.of(limit), clock).allowed.assert().isTrue()
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

    companion object {
        fun connectionFactory(host: String, port: Int): LettuceConnectionFactory =
            LettuceConnectionFactory(RedisStandaloneConfiguration(host, port)).apply {
                afterPropertiesSet()
                start()
            }
    }
}

/** The 7.x quota of a spec, as the pipeline's limiters read it. */
internal object RedisRateLimiterTestLimits {
    fun of(limit: RateLimitSpec) = me.ahoo.cosec.runtime.ratelimit.RateLimiter.window(limit)
}
