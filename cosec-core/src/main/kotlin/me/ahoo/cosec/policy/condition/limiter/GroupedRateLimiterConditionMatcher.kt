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

package me.ahoo.cosec.policy.condition.limiter

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.util.concurrent.RateLimiter
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.part.PartConditionMatcher
import java.util.concurrent.TimeUnit

const val GROUPED_RATE_LIMITER_CONDITION_MATCHER_EXPIRE_AFTER_ACCESS_SECOND_KEY = "expireAfterAccessSecond"

class GroupedRateLimiterConditionMatcher(
    configuration: Configuration
) : PartConditionMatcher(RateLimiterConditionMatcherFactory.TYPE, configuration) {

    private val permitsPerSecond: Double =
        requireNotNull(configuration.get(RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY)) {
            "permitsPerSecond is required!"
        }.asDouble()
    private val expireAfterAccessSecond: Long =
        requireNotNull(configuration.get(GROUPED_RATE_LIMITER_CONDITION_MATCHER_EXPIRE_AFTER_ACCESS_SECOND_KEY)) {
            "expireAfterAccessSecond is required!"
        }.asLong()
    private var rateLimiters: LoadingCache<String, RateLimiter> = CacheBuilder.newBuilder()
        .expireAfterAccess(expireAfterAccessSecond, TimeUnit.SECONDS)
        .build(RateLimiterLoader())

    override fun matchPart(partValue: String, securityContext: SecurityContext): Boolean {
        val rateLimiter = rateLimiters.get(partValue)
        if (rateLimiter.tryAcquire()) {
            return true
        }

        throw TooManyRequestsException()
    }

    inner class RateLimiterLoader : CacheLoader<String, RateLimiter>() {
        override fun load(key: String): RateLimiter {
            return RateLimiter.create(permitsPerSecond)
        }
    }
}

class GroupedRateLimiterConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "groupedRateLimiter"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return GroupedRateLimiterConditionMatcher(configuration)
    }
}
