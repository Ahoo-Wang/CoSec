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

import kotlinx.coroutines.future.await
import me.ahoo.cosec.limiter.RateLimitResult
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.ratelimit.RateLimiter
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

/**
 * The cluster-wide [RateLimiter] of the coroutine pipeline (RFC 0006 §8.4): the sliding window of [RedisRateLimiter], the
 * same Lua script and the same keys (`<keyPrefix>:<ruleId>:<bucket>:<windowIndex>`), so instances of both count in the
 * same buckets during a rolling upgrade. Run through the reactive template and awaited: the caller's thread never waits
 * for Redis, and Reactor stays inside this class (RFC 0006 K9).
 *
 * A Redis failure is thrown; the rule's `strictFailure` decides what it means.
 */
class ReactiveRedisRateLimiter(
    private val template: ReactiveStringRedisTemplate,
    keyPrefix: String = DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX,
    private val clock: () -> Long = System::currentTimeMillis,
) : RateLimiter {
    private val keyPrefix: String = keyPrefix.trimEnd(':')

    override suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult {
        val rateLimit = RateLimiter.window(limit)
        val nowMillis = clock()
        val windowIndex = nowMillis / rateLimit.windowMillis
        val baseKey = "$keyPrefix:$ruleId:$bucket"
        val reply = template.execute(
            RedisRateLimiter.SCRIPT,
            listOf("$baseKey:$windowIndex", "$baseKey:${windowIndex - 1}"),
            listOf(rateLimit.limitPerWindow.toString(), rateLimit.windowMillis.toString(), nowMillis.toString()),
        ).next().toFuture().await() ?: error("Redis rate limiter script returned no reply.")
        val allowed = reply[0] == 1L
        val estimate = reply[1] / RedisRateLimiter.ESTIMATE_SCALE
        return rateLimit.resultOf(allowed, estimate, nowMillis)
    }
}
