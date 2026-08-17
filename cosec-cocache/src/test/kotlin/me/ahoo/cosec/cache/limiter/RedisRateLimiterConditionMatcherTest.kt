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

import io.mockk.mockk
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.condition.limiter.RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY
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

class RedisRateLimiterConditionMatcherTest {

    @Test
    fun matchAllowsRequestsWithinWindowLimit() {
        // windowSeconds=5 -> quota per window is 2*5=10; the larger window keeps the
        // burst-fill assertions robust against scheduler pauses (a gap between calls
        // only flips the outcome once it exceeds half the window).
        val conditionMatcher = factory()
            .create(
                mapOf(
                    RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 2,
                    REDIS_RATE_LIMITER_CONDITION_MATCHER_WINDOW_SECONDS_KEY to 5,
                ).asConfiguration(),
            )
        repeat(WINDOW_QUOTA) {
            conditionMatcher.match(mockk(), mockk()).assert().isTrue()
        }
        assertThrows(TooManyRequestsException::class.java) {
            conditionMatcher.match(mockk(), mockk())
        }
    }

    @Test
    fun matchRecoversAfterWindowRolls() {
        val conditionMatcher = factory()
            .create(mapOf(RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 2).asConfiguration())
        conditionMatcher.match(mockk(), mockk()).assert().isTrue()
        conditionMatcher.match(mockk(), mockk()).assert().isTrue()
        assertThrows(TooManyRequestsException::class.java) {
            conditionMatcher.match(mockk(), mockk())
        }
        // After the window rolls, the previous window's weight decays and requests are allowed again.
        Thread.sleep(WINDOW_ROLL_MARGIN_MS)
        conditionMatcher.match(mockk(), mockk()).assert().isTrue()
    }

    @Test
    fun failOpenWhenRedisUnavailable() {
        val conditionMatcher = badFactory()
            .create(mapOf(RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 3).asConfiguration())
        conditionMatcher.match(mockk(), mockk()).assert().isTrue()
    }

    @Test
    fun failClosedWhenRedisUnavailableAndStrict() {
        val conditionMatcher = badFactory()
            .create(
                mapOf(
                    RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 3,
                    REDIS_RATE_LIMITER_CONDITION_MATCHER_STRICT_FAILURE_KEY to true,
                ).asConfiguration(),
            )
        assertThrows(TooManyRequestsException::class.java) {
            conditionMatcher.match(mockk(), mockk())
        }
    }

    companion object {
        private const val WINDOW_ROLL_MARGIN_MS = 1_200L
        private const val WINDOW_QUOTA = 10

        private lateinit var stringRedisTemplate: StringRedisTemplate
        private lateinit var badStringRedisTemplate: StringRedisTemplate

        @BeforeAll
        @JvmStatic
        fun setup() {
            val connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
            connectionFactory.afterPropertiesSet()
            stringRedisTemplate = StringRedisTemplate(connectionFactory)

            val badConnectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", UNUSED_PORT))
            badConnectionFactory.afterPropertiesSet()
            badStringRedisTemplate = StringRedisTemplate(badConnectionFactory)
        }

        @AfterAll
        @JvmStatic
        fun destroy() {
            stringRedisTemplate.connectionFactory?.let { (it as LettuceConnectionFactory).destroy() }
            badStringRedisTemplate.connectionFactory?.let { (it as LettuceConnectionFactory).destroy() }
        }

        private fun factory(): RedisRateLimiterConditionMatcherFactory =
            // Random key prefix isolates each run: window keys live for 2 windows (10s with
            // windowSeconds=5), so reuse across runs would leak counters into the next run.
            RedisRateLimiterConditionMatcherFactory(
                stringRedisTemplate,
                keyPrefix = "test:rl:${UUID.randomUUID()}",
            )

        private fun badFactory(): RedisRateLimiterConditionMatcherFactory =
            RedisRateLimiterConditionMatcherFactory(badStringRedisTemplate)

        private const val UNUSED_PORT = 6_399
    }
}
