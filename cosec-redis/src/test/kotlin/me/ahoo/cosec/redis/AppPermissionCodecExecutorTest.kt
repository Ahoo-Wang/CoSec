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

import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
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
 * AppPermissionCodecExecutorTest .
 *
 * @author ahoo wang
 */
internal class AppPermissionCodecExecutorTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: ObjectToJsonCodecExecutor<AppPermission>
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory

    @BeforeEach
    fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        codecExecutor = ObjectToJsonCodecExecutor(AppPermission::class.java, stringRedisTemplate, CoSecJsonSerializer)
    }

    @AfterEach
    fun destroy() {
        lettuceConnectionFactory.destroy()
    }

    @Test
    fun executeAndEncode() {
        val appPermission = AppPermissionData(
            id = "2",
            groups = listOf(
                PermissionGroupData(
                    "groupName",
                    permissions = listOf(PermissionData("id", "name", action = AllActionMatcher.INSTANCE)),
                ),
            ),
        )
        val key = "app:" + MockIdGenerator.INSTANCE.generateAsString()
        val cacheValue: CacheValue<AppPermission> = DefaultCacheValue.forever(appPermission)
        codecExecutor.executeAndEncode(key, cacheValue)
        val value = codecExecutor.executeAndDecode(key, ComputedTtlAt.FOREVER).value
        assertThat(value, `is`(appPermission))
    }

    @Test
    fun executeAndEncodeMissing() {
        val key = "app:" + MockIdGenerator.INSTANCE.generateAsString()
        val cacheValue: CacheValue<AppPermission> = DefaultCacheValue.missingGuard()
        codecExecutor.executeAndEncode(key, cacheValue)
        val actual = codecExecutor.executeAndDecode(key, ComputedTtlAt.FOREVER)
        assertThat(actual, `is`(cacheValue))
    }
}
