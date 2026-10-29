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

package me.ahoo.cosec.social.justauth

import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

internal class RedisAuthStateCacheTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var redisAuthStateCache: RedisAuthStateCache

    @BeforeEach
    fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        redisAuthStateCache = RedisAuthStateCache(stringRedisTemplate)
    }

    @AfterEach
    fun destroy() {
        lettuceConnectionFactory.destroy()
    }

    @Test
    fun cache() {
        val key = MockIdGenerator.INSTANCE.generateAsString()
        redisAuthStateCache.containsKey(key).assert().isEqualTo(false)
        val value = MockIdGenerator.INSTANCE.generateAsString()
        redisAuthStateCache.cache(key, value)
        val cacheValue = redisAuthStateCache.get(key)
        cacheValue.assert().isEqualTo(value)
        redisAuthStateCache.containsKey(key).assert().isEqualTo(true)
        redisAuthStateCache.cache(key, value, 100)
        Thread.sleep(150)
        redisAuthStateCache.containsKey(key).assert().isEqualTo(false)
    }
}
