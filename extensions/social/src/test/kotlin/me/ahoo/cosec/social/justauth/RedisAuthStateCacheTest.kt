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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.social.justauth

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.id.RandomIdGenerator
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

    /**
     * JustAuth's state key is frozen within 8.x (RFC 0014 R6, `SNP-26`, `FrozenLayout.SOCIAL_STATE` of `cosec-cocache`):
     * a callback that reaches a release deriving it otherwise finds no state.
     */
    @Test
    fun theStateKeyIsFrozen() {
        mapOf("cosec" to "cosec:oauth:state:", "{cosec}" to "{cosec}:oauth:state:").forEach { (prefix, expected) ->
            RedisAuthStateCache.keyPrefix(prefix).assert()
                .describedAs(
                    "The keys of a social sign-in in progress are a format frozen within 8.x (RFC 0014 R6, SNP-26, " +
                        "FrozenLayout.SOCIAL_STATE): a mixed deployment that derives them apart refuses what both releases " +
                        "accept. Changing it needs a new key space, written once every reader can read it (R5)."
                )
                .isEqualTo(expected)
        }
        val prefix = "test-" + RandomIdGenerator.generate()
        val key = RandomIdGenerator.generate()
        RedisAuthStateCache(stringRedisTemplate, RedisAuthStateCache.keyPrefix(prefix)).cache(key, "v")
        stringRedisTemplate.hasKey("$prefix:oauth:state:$key").assert().isTrue()
        stringRedisTemplate.delete("$prefix:oauth:state:$key")
    }

    @Test
    fun cache() {
        val key = RandomIdGenerator.generate()
        redisAuthStateCache.containsKey(key).assert().isEqualTo(false)
        val value = RandomIdGenerator.generate()
        redisAuthStateCache.cache(key, value)
        val cacheValue = redisAuthStateCache.get(key)
        cacheValue.assert().isEqualTo(value)
        redisAuthStateCache.containsKey(key).assert().isEqualTo(true)
        redisAuthStateCache.cache(key, value, 100)
        Thread.sleep(150)
        redisAuthStateCache.containsKey(key).assert().isEqualTo(false)
    }

    /** The state is stored with the time to live that was asked for, in milliseconds, and the default's is three minutes. */
    @Test
    fun theTimeToLiveIsTheOneAsked() {
        val key = RandomIdGenerator.generate()
        redisAuthStateCache.cache(key, "v", 60_000)
        val asked = stringRedisTemplate.getExpire("cosec:oauth:state:$key", java.util.concurrent.TimeUnit.MILLISECONDS)
        asked.assert().isBetween(1L, 60_000L)
        val other = RandomIdGenerator.generate()
        redisAuthStateCache.cache(other, "v")
        val default = stringRedisTemplate.getExpire(
            "cosec:oauth:state:$other",
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        default.assert().isBetween(60_001L, 180_000L)
    }

    /** M4: JustAuth may read a state that is not there; that is an empty value, never an exception. */
    @Test
    fun aMissingStateIsEmpty() {
        redisAuthStateCache.get(RandomIdGenerator.generate()).assert().isEmpty()
    }
}
