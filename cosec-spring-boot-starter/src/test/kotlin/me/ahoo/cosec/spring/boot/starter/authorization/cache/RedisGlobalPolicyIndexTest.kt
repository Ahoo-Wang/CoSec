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
package me.ahoo.cosec.spring.boot.starter.authorization.cache

import io.mockk.every
import io.mockk.spyk
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class RedisGlobalPolicyIndexTest {
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var key: String

    @BeforeEach
    fun setup() {
        connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        key = "cosec:test:global-policy-index:${UUID.randomUUID()}"
    }

    @AfterEach
    fun destroy() {
        redisTemplate.delete(listOf(key, "$key:pending"))
        connectionFactory.destroy()
    }

    @Test
    fun retainsConcurrentAddsAcrossInstancesAndRemovesAtomically() {
        val firstIndex = RedisGlobalPolicyIndex(redisTemplate, key)
        val secondIndex = RedisGlobalPolicyIndex(redisTemplate, key)
        val policyIds = (1..100).map { "policy-$it" }
        val executor = Executors.newFixedThreadPool(8)
        try {
            executor.invokeAll(
                policyIds.mapIndexed { index, policyId ->
                    Callable {
                        val indexAdapter = if (index % 2 == 0) {
                            firstIndex
                        } else {
                            secondIndex
                        }
                        indexAdapter.update(policyId, true) {}
                    }
                }
            ).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        firstIndex.getPolicyIds().assert().isEqualTo(policyIds.toSet())
        redisTemplate.opsForSet().members("$key:pending").orEmpty().assert().isEmpty()

        secondIndex.update("policy-1", false) {}

        firstIndex.getPolicyIds().assert().isEqualTo(policyIds.toSet() - "policy-1")
        redisTemplate.opsForSet().members("$key:pending").orEmpty().assert().isEmpty()
    }

    @Test
    fun serializesConflictingUpdatesForSamePolicyAcrossInstances() {
        val lockTtl = Duration.ofMillis(100)
        val lockAcquireTimeout = Duration.ofSeconds(2)
        val firstIndex = RedisGlobalPolicyIndex(redisTemplate, key, lockTtl, lockAcquireTimeout)
        val secondIndex = RedisGlobalPolicyIndex(redisTemplate, key, lockTtl, lockAcquireTimeout)
        val policyId = "policy"
        val storedType = AtomicReference(PolicyType.GLOBAL)
        val systemWriteStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val systemUpdate = executor.submit {
                firstIndex.update(policyId, false) {
                    storedType.set(PolicyType.SYSTEM)
                    systemWriteStarted.countDown()
                    Thread.sleep(lockTtl.toMillis() * 3)
                }
            }
            systemWriteStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            val globalUpdate = executor.submit {
                secondIndex.update(policyId, true) {
                    storedType.set(PolicyType.GLOBAL)
                }
            }

            systemUpdate.get()
            globalUpdate.get()
        } finally {
            executor.shutdownNow()
        }

        storedType.get().assert().isEqualTo(PolicyType.GLOBAL)
        firstIndex.getPolicyIds().assert().contains(policyId)
    }

    @Test
    fun retriesWhenOwnershipCheckFails() {
        val faultInjectingRedisTemplate = spyk(redisTemplate) {
            every {
                execute(
                    match<RedisScript<Long>> { it.scriptAsString.contains("pexpire") },
                    any<List<String>>(),
                    *anyVararg(),
                )
            } throws RedisConnectionFailureException("simulated ownership-check failure")
        }
        val index = RedisGlobalPolicyIndex(
            faultInjectingRedisTemplate,
            key,
            Duration.ofSeconds(30),
            Duration.ofSeconds(2),
        )
        val policyWrites = AtomicInteger()

        assertThrows(IllegalStateException::class.java) {
            index.update("policy", true) {
                policyWrites.incrementAndGet()
            }
        }

        policyWrites.get().assert().isEqualTo(3)
        redisTemplate.opsForSet().remove(key, "policy")
        index.getPolicyIds().assert().contains("policy")
    }
}
