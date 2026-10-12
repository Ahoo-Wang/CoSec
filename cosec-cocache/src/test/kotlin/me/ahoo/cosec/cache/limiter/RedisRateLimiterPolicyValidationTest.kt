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
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.BOOL_CONDITION_MATCHER_OR_KEY
import me.ahoo.cosec.policy.condition.BoolConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.cosec.policy.condition.limiter.RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.policy.condition.part.CONDITION_MATCHER_PART_KEY
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import reactor.kotlin.test.test
import java.util.UUID

/**
 * Saving a policy validates it with `DefaultPolicyEvaluator`, which runs every condition against a mock request.
 * Redis rate limiter keys depend only on their configuration, so a validation that reached Redis would spend
 * permits from the live quota shared by every policy with the same limits. Runs against real Redis.
 */
class RedisRateLimiterPolicyValidationTest {
    companion object {
        private const val NESTED_GROUPED_TYPE = "testNestedRedisGroupedRateLimiter"

        /** After one acquire the estimate exceeds the quota, so the second real acquire must be rejected. */
        private const val TINY_PERMITS_PER_SECOND = 0.0000001

        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var stringRedisTemplate: StringRedisTemplate

        // Random per run: window keys outlive the test, and a shared prefix would hide a leaked permit.
        private val keyPrefix = "test:rl-validation:${UUID.randomUUID()}"

        @BeforeAll
        @JvmStatic
        fun setup() {
            connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
            connectionFactory.afterPropertiesSet()
            stringRedisTemplate = StringRedisTemplate(connectionFactory)
            val groupedFactory = RedisGroupedRateLimiterConditionMatcherFactory(stringRedisTemplate, keyPrefix)
            ConditionMatcherFactoryProvider.register(
                object : ConditionMatcherFactory {
                    override val type: String = NESTED_GROUPED_TYPE
                    override fun create(configuration: Configuration): ConditionMatcher =
                        groupedFactory.create(configuration)
                },
            )
        }

        @AfterAll
        @JvmStatic
        fun destroy() {
            connectionFactory.destroy()
        }
    }

    private fun limiterKeys(): Set<String> = stringRedisTemplate.keys("$keyPrefix:*")

    @Test
    fun setPolicyLeavesRateLimiterCountersUntouched() {
        val limiter = RedisRateLimiterConditionMatcherFactory(stringRedisTemplate, keyPrefix).create(
            mapOf(RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to TINY_PERMITS_PER_SECOND).asConfiguration(),
        )
        val groupedLimiter = RedisGroupedRateLimiterConditionMatcherFactory(stringRedisTemplate, keyPrefix).create(
            mapOf(
                RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to TINY_PERMITS_PER_SECOND,
                CONDITION_MATCHER_PART_KEY to "request.remoteIp",
            ).asConfiguration(),
        )
        val nestedLimiter = BoolConditionMatcherFactory().create(
            mapOf(
                BOOL_CONDITION_MATCHER_OR_KEY to listOf(
                    mapOf(
                        NESTED_GROUPED_TYPE to mapOf(
                            RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 1,
                            CONDITION_MATCHER_PART_KEY to "request.remoteIp",
                        ),
                    ),
                ),
            ).asConfiguration(),
        )
        val policy = PolicyData(
            id = "rate-limited",
            category = "",
            name = "",
            description = "",
            type = PolicyType.GLOBAL,
            tenantId = "",
            condition = limiter,
            statements = listOf(
                StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE, condition = groupedLimiter),
                StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE, condition = nestedLimiter),
            ),
        )
        // The caches are not under test here; the limiter's Redis counters are.
        val repository = RedisPolicyRepository(
            mockk<GlobalPolicyIndexCache>(relaxed = true),
            mockk<PolicyCache>(relaxed = true),
        )

        repository.setPolicy(policy).test().verifyComplete()

        limiterKeys().assert().isEmpty()

        // Real requests still acquire, and still fail closed once the full quota is spent.
        val request = EvaluateRequest()
        val context = SimpleSecurityContext.anonymous()
        limiter.match(request, context).assert().isTrue()
        limiterKeys().assert().isNotEmpty()
        assertThrows(TooManyRequestsException::class.java) {
            limiter.match(request, context)
        }
        groupedLimiter.match(request, context).assert().isTrue()
        assertThrows(TooManyRequestsException::class.java) {
            groupedLimiter.match(request, context)
        }
    }
}
