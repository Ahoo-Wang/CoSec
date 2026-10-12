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

import me.ahoo.cosec.apikey.ApiKey
import me.ahoo.cosec.apikey.ApiKeySecurityContextParser
import me.ahoo.cosec.apikey.ApiKeys
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID

class RedisApiKeyStoreTest {
    private val store = RedisApiKeyStore(stringRedisTemplate, "test:api-key:${UUID.randomUUID()}")

    @Test
    fun issueFindAndRevoke() {
        val apiKey = ApiKey(
            id = "billing",
            principalId = "svc-billing",
            tenantId = "t1",
            roles = setOf("billing"),
            attributes = mapOf("team" to "finance"),
            expiresAt = Instant.parse("2999-01-01T00:00:00Z"),
        )
        val key = store.issue(apiKey)
        val hash = ApiKeys.hash(key)
        store.findByHash(hash).assert().isEqualTo(apiKey)
        // Only the hash is stored: the key itself is not a Redis key or value.
        stringRedisTemplate.keys("*$key*").assert().isEmpty()

        val parser = ApiKeySecurityContextParser(SecurityContextParser { SimpleSecurityContext.anonymous() }, store)
        parser.parse(EvaluateRequest(headers = mapOf(ApiKeySecurityContextParser.DEFAULT_HEADER to key)))
            .principal.id.assert().isEqualTo("svc-billing")

        store.save(hash, apiKey.copy(enabled = false))
        store.findByHash(hash)?.enabled.assert().isEqualTo(false)

        store.revoke(hash).assert().isTrue()
        store.findByHash(hash).assert().isNull()
    }

    companion object {
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
    }
}
