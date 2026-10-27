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
import me.ahoo.cosec.limiter.RateLimitResult
import me.ahoo.cosec.limiter.RateLimiter
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

const val DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX = "cosec:rate-limiter"

/**
 * Cluster-wide [RateLimiter]: a sliding-window counter kept in Redis, checked and counted atomically by a Lua
 * script. Each call is a blocking Redis round trip, so it runs on [Schedulers.boundedElastic].
 *
 * Counters live under `<keyPrefix>:<ruleId>:<bucket>:<windowIndex>`. Redis failures are signalled as errors.
 */
class RedisRateLimiter(
    private val stringRedisTemplate: StringRedisTemplate,
    keyPrefix: String = DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX,
    private val clock: () -> Long = System::currentTimeMillis
) : RateLimiter {
    companion object {
        /** Script resource next to this class, see `redis-rate-limiter.lua` in the module resources. */
        private const val SCRIPT_PATH = "redis-rate-limiter.lua"

        /** The script returns the estimate in thousandths, since Redis truncates Lua numbers to integers. */
        internal const val ESTIMATE_SCALE = 1_000.0

        @Suppress("UNCHECKED_CAST")
        internal val SCRIPT: DefaultRedisScript<List<Long>> = DefaultRedisScript(
            loadScript(),
            List::class.java as Class<List<Long>>,
        )

        private fun loadScript(): String {
            val script = RedisRateLimiter::class.java.getResourceAsStream(SCRIPT_PATH)
                ?: error("Redis rate limiter script [$SCRIPT_PATH] not found on the classpath.")
            return script.bufferedReader().use { it.readText() }
        }
    }

    private val keyPrefix: String = keyPrefix.trimEnd(':')

    override fun tryAcquire(ruleId: String, bucket: String, rateLimit: RateLimit): Mono<RateLimitResult> =
        Mono.fromCallable { acquire(ruleId, bucket, rateLimit, clock()) }
            .subscribeOn(Schedulers.boundedElastic())

    internal fun acquire(ruleId: String, bucket: String, rateLimit: RateLimit, nowMillis: Long): RateLimitResult {
        val windowIndex = nowMillis / rateLimit.windowMillis
        val baseKey = "$keyPrefix:$ruleId:$bucket"
        val reply = checkNotNull(
            stringRedisTemplate.execute(
                SCRIPT,
                listOf("$baseKey:$windowIndex", "$baseKey:${windowIndex - 1}"),
                rateLimit.limitPerWindow.toString(),
                rateLimit.windowMillis.toString(),
                nowMillis.toString(),
            ),
        ) { "Redis rate limiter script returned no reply." }
        val allowed = reply[0] == 1L
        val estimate = reply[1] / ESTIMATE_SCALE
        return rateLimit.resultOf(allowed, estimate, nowMillis)
    }
}
