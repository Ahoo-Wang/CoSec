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

import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

class RedisPolicyRevisionStoreTest {
    private val store = RedisPolicyRevisionStore(
        stringRedisTemplate,
        "test:policy-history:${UUID.randomUUID()}",
        maxRevisions = 2
    )

    private fun policy(name: String) = PolicyData(
        "orders",
        "",
        name,
        "",
        PolicyType.GLOBAL,
        "t",
        statements = listOf(StatementData(name = "all", action = AllActionMatcher.INSTANCE)),
    )

    @Test
    fun appendHistoryAndTrim() {
        store.append(policy("v1"), "alice", "")
        store.append(policy("v2"), "bob", "fix")
        store.append(policy("v3"), "carol", "")
        val history = store.history("orders")
        history.map { it.revision }.assert().containsExactly(3L, 2L)
        history.last().apply {
            author.assert().isEqualTo("bob")
            note.assert().isEqualTo("fix")
            policy.name.assert().isEqualTo("v2")
            policy.statements.single().name.assert().isEqualTo("all")
        }
        store.get("orders", 3)?.policy?.name.assert().isEqualTo("v3")
        store.get("orders", 1).assert().isNull()
        store.history("unknown").assert().isEmpty()
    }

    @Test
    fun rejectsNonPositiveMax() {
        assertThrows(
            IllegalArgumentException::class.java
        ) { RedisPolicyRevisionStore(stringRedisTemplate, maxRevisions = 0) }
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
