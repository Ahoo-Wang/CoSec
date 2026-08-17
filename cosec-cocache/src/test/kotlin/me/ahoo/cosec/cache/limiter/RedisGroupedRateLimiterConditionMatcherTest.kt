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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.condition.limiter.RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.policy.condition.part.CONDITION_MATCHER_PART_KEY
import me.ahoo.cosec.policy.condition.part.RequestParts
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

class RedisGroupedRateLimiterConditionMatcherTest {

    @Test
    fun matchAllowsIndependentlyPerGroup() {
        val conditionMatcher = factory()
            .create(
                mapOf(
                    CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                    RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 2,
                    REDIS_RATE_LIMITER_CONDITION_MATCHER_WINDOW_SECONDS_KEY to 5,
                ).asConfiguration(),
            )
        val requestA: Request = mockk {
            every { remoteIp } returns "ip-a"
        }
        val requestB: Request = mockk {
            every { remoteIp } returns "ip-b"
        }
        // windowSeconds=5 -> quota per window is 2*5=10.
        repeat(WINDOW_QUOTA) {
            conditionMatcher.match(requestA, mockk()).assert().isTrue()
        }
        assertThrows(TooManyRequestsException::class.java) {
            conditionMatcher.match(requestA, mockk())
        }
        // Group B has its own independent quota.
        conditionMatcher.match(requestB, mockk()).assert().isTrue()
    }

    companion object {
        private const val WINDOW_QUOTA = 10

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

        private fun factory(): RedisGroupedRateLimiterConditionMatcherFactory =
            // Random key prefix isolates each run: window keys live for 2 windows (10s with
            // windowSeconds=5), so reuse across runs would leak counters into the next run.
            RedisGroupedRateLimiterConditionMatcherFactory(
                stringRedisTemplate,
                keyPrefix = "test:rl:${UUID.randomUUID()}",
            )
    }
}
