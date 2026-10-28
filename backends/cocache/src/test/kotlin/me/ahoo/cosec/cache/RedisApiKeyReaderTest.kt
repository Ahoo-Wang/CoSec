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

package me.ahoo.cosec.cache

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.apikey.ApiKeys
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID

/** [RedisApiKeyReader] against real Redis: it reads what [RedisApiKeyStore] writes, and a Redis failure throws. */
class RedisApiKeyReaderTest {
    private val keyPrefix = "test:api-key:${UUID.randomUUID()}:"
    private val store = RedisApiKeyStore(StringRedisTemplate(factory), keyPrefix)
    private val reader = RedisApiKeyReader(ReactiveStringRedisTemplate(factory), keyPrefix)

    @Test
    fun readsWhatTheStoreWrites() = runBlocking<Unit> {
        val apiKey = ApiKey(id = "billing", principalId = "svc-billing", tenantId = "t1", roles = setOf("billing"))
        val hash = ApiKeys.hash(store.issue(apiKey))
        reader.findByHash(hash).assert().isEqualTo(apiKey)
        reader.findByHash(ApiKeys.hash("unknown")).assert().isNull()

        store.save(hash, apiKey.copy(enabled = false))
        reader.findByHash(hash)?.enabled.assert().isEqualTo(false)
        store.revoke(hash)
        reader.findByHash(hash).assert().isNull()
    }

    /** Fail closed: an unreachable Redis is an error, never "no such key". */
    @Test
    fun anUnreachableRedisThrows() {
        val unreachable = LettuceConnectionFactory(
            RedisStandaloneConfiguration("localhost", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build(),
        )
        unreachable.afterPropertiesSet()
        try {
            val failing = RedisApiKeyReader(ReactiveStringRedisTemplate(unreachable), keyPrefix)
            assertThrows<Exception> { runBlocking { failing.findByHash(ApiKeys.hash("any")) } }
        } finally {
            unreachable.destroy()
        }
    }

    companion object {
        private lateinit var factory: LettuceConnectionFactory

        @BeforeAll
        @JvmStatic
        fun setup() {
            factory = LettuceConnectionFactory(RedisStandaloneConfiguration())
            factory.afterPropertiesSet()
        }

        @AfterAll
        @JvmStatic
        fun destroy() {
            factory.destroy()
        }
    }
}
