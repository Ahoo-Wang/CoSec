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

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.limiter.RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY
import me.ahoo.cosec.policy.condition.part.PartConditionMatcher
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Distributed rate limiter condition matcher backed by Redis, grouped by a part
 * value (e.g. `request.remoteIp`), so each group gets its own quota.
 */
class RedisGroupedRateLimiterConditionMatcher(
    configuration: Configuration,
    private val stringRedisTemplate: StringRedisTemplate,
    private val keyPrefix: String = DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX
) : PartConditionMatcher(RedisGroupedRateLimiterConditionMatcherFactory.TYPE, configuration) {

    private val permitsPerSecond: Double =
        requireNotNull(configuration.get(RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY)) {
            "permitsPerSecond is required!"
        }.asDouble()

    private val windowSeconds: Long = configuration
        .get(REDIS_RATE_LIMITER_CONDITION_MATCHER_WINDOW_SECONDS_KEY)?.asLong() ?: 1

    private val strictFailure: Boolean = configuration
        .get(REDIS_RATE_LIMITER_CONDITION_MATCHER_STRICT_FAILURE_KEY)?.asBoolean() ?: false

    private val rateLimiter = RedisSlidingWindowRateLimiter(
        stringRedisTemplate = stringRedisTemplate,
        keyPrefix = keyPrefix,
        permitsPerSecond = permitsPerSecond,
        windowSeconds = windowSeconds,
        strictFailure = strictFailure,
    )

    override fun matchPart(partValue: String, securityContext: SecurityContext): Boolean {
        return rateLimiter.tryAcquire(partValue)
    }
}

class RedisGroupedRateLimiterConditionMatcherFactory(
    private val stringRedisTemplate: StringRedisTemplate,
    private val keyPrefix: String = DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX
) : ConditionMatcherFactory {
    companion object {
        const val TYPE = "redisGroupedRateLimiter"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return RedisGroupedRateLimiterConditionMatcher(configuration, stringRedisTemplate, keyPrefix)
    }
}
