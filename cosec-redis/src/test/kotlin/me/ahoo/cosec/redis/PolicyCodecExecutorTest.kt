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
package me.ahoo.cosec.redis

import me.ahoo.cache.CacheValue
import me.ahoo.cache.TtlAt
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosid.test.MockIdGenerator
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * PolicyCodecExecutorTest .
 *
 * @author ahoo wang
 */
internal class PolicyCodecExecutorTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: ObjectToJsonCodecExecutor<Policy>
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory

    @BeforeEach
    fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        codecExecutor = ObjectToJsonCodecExecutor(Policy::class.java, stringRedisTemplate, CoSecJsonSerializer)
    }

    @AfterEach
    fun destroy() {
        lettuceConnectionFactory.destroy()
    }

    @Test
    fun executeAndEncode() {
        val policy = PolicyData(
            id = "2",
            category = "auth",
            name = "auth",
            description = "",
            type = PolicyType.SYSTEM,
            tenantId = "1",
            statements = listOf(StatementData(action = AllActionMatcher)),
        )
        val key = "policy:" + MockIdGenerator.INSTANCE.generateAsString()
        val cacheValue: CacheValue<Policy> = CacheValue.forever(policy)
        codecExecutor.executeAndEncode(key, cacheValue)
        val (value) = codecExecutor.executeAndDecode(key, TtlAt.FOREVER)
        assertThat(value, `is`(policy))
    }

    @Test
    fun executeAndEncodeMissing() {
        val key = "policy:" + MockIdGenerator.INSTANCE.generateAsString()
        val cacheValue: CacheValue<Policy> = CacheValue.missingGuard()
        codecExecutor.executeAndEncode(key, cacheValue)
        val actual = codecExecutor.executeAndDecode(key, TtlAt.FOREVER)
        assertThat(actual, `is`(cacheValue))
    }
}
