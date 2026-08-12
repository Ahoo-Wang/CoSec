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

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import reactor.core.publisher.Mono
import reactor.core.scheduler.NonBlocking
import reactor.core.scheduler.Schedulers
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class RedisPolicyStoreTest {
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var policyStoreKey: String
    private lateinit var globalPolicyStoreKey: String
    private lateinit var legacyPolicyKeyPrefix: String
    private lateinit var legacyGlobalPolicyIndexKey: String
    private lateinit var store: RedisPolicyStore

    private val globalPolicy = policy(PolicyType.GLOBAL)
    private val systemPolicy = policy(PolicyType.SYSTEM)

    @BeforeEach
    fun setup() {
        connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        val keySuffix = UUID.randomUUID().toString()
        val policyStoreHashTag = "{cosec:test:policy-store:$keySuffix}"
        policyStoreKey = "$policyStoreHashTag:store"
        globalPolicyStoreKey = "$policyStoreHashTag:global"
        legacyPolicyKeyPrefix = "cosec:test:policy:$keySuffix:"
        legacyGlobalPolicyIndexKey = "cosec:test:global-policy:$keySuffix"
        store = RedisPolicyStore(
            redisTemplate,
            CoSecJsonSerializer,
            policyStoreKey,
            globalPolicyStoreKey,
            legacyPolicyKeyPrefix,
            legacyGlobalPolicyIndexKey,
        )
    }

    @AfterEach
    fun destroy() {
        redisTemplate.delete(
            listOf(
                policyStoreKey,
                globalPolicyStoreKey,
                "$legacyPolicyKeyPrefix${globalPolicy.id}",
                legacyGlobalPolicyIndexKey,
                "$legacyGlobalPolicyIndexKey:pending",
            )
        )
        connectionFactory.destroy()
    }

    @Test
    fun policyTypeTransitionIsReadFromOneAuthoritativeRecord() {
        store.setPolicy(globalPolicy).block()

        store.getGlobalPolicies().block().orEmpty().assert().containsExactly(globalPolicy)
        store.getPolicies(setOf(globalPolicy.id)).block().orEmpty().assert().containsExactly(globalPolicy)

        store.setPolicy(systemPolicy).block()

        store.getGlobalPolicies().block().orEmpty().assert().isEmpty()
        store.getPolicies(setOf(systemPolicy.id)).block().orEmpty().assert().containsExactly(systemPolicy)
    }

    @Test
    fun cacheFacadeUsesTheSameAuthoritativeRecord() {
        store[globalPolicy.id] = globalPolicy

        store[globalPolicy.id].assert().isEqualTo(globalPolicy)
        store.getCache(globalPolicy.id)?.value.assert().isEqualTo(globalPolicy)
        store.getTtlAt(globalPolicy.id).assert().isEqualTo(Long.MAX_VALUE)

        store.evict(globalPolicy.id)

        store[globalPolicy.id].assert().isNull()
        store.getGlobalPolicies().block().orEmpty().assert().isEmpty()
    }

    @Test
    fun cacheFacadeSupportsCacheValueWritesAndRejectsMismatchedKeys() {
        store.set(globalPolicy.id, Long.MAX_VALUE, globalPolicy)
        store[globalPolicy.id].assert().isEqualTo(globalPolicy)

        store.setCache(systemPolicy.id, DefaultCacheValue.forever(systemPolicy))
        store[systemPolicy.id].assert().isEqualTo(systemPolicy)

        store.setCache(systemPolicy.id, DefaultCacheValue(systemPolicy, 0))
        store[systemPolicy.id].assert().isNull()

        assertThrownBy<IllegalArgumentException> {
            store["another-policy"] = globalPolicy
        }.hasMessage("Policy cache key must match policy id.")
    }

    @Test
    fun emptyAndMissingReadsDoNotCreateRecords() {
        store.getPolicies(emptySet()).block().orEmpty().assert().isEmpty()
        store.getPolicies(setOf("missing")).block().orEmpty().assert().isEmpty()
        store["missing"].assert().isNull()
        store.getCache("missing").assert().isNull()
        store.getTtlAt("missing").assert().isNull()
        redisTemplate.opsForHash<String, String>().size(policyStoreKey).assert().isZero()
        redisTemplate.opsForHash<String, String>().size(globalPolicyStoreKey).assert().isZero()
    }

    @Test
    fun migratesLegacyPolicyAndGlobalIndexOnRead() {
        val serializedPolicy = CoSecJsonSerializer.writeValueAsString(globalPolicy)
        redisTemplate.opsForValue()["$legacyPolicyKeyPrefix${globalPolicy.id}"] = serializedPolicy
        redisTemplate.opsForSet().add(legacyGlobalPolicyIndexKey, globalPolicy.id)

        store.getGlobalPolicies().block().orEmpty().assert().containsExactly(globalPolicy)
        redisTemplate.opsForHash<String, String>()[policyStoreKey, globalPolicy.id]
            .assert().isEqualTo(serializedPolicy)
        redisTemplate.opsForHash<String, String>()[globalPolicyStoreKey, globalPolicy.id]
            .assert().isEqualTo(serializedPolicy)
        redisTemplate.opsForSet().members(legacyGlobalPolicyIndexKey).orEmpty().assert().isEmpty()
    }

    @Test
    fun legacyMigrationCannotOverwriteAuthoritativeWrite() {
        val serializedLegacyPolicy = CoSecJsonSerializer.writeValueAsString(globalPolicy)
        redisTemplate.opsForValue()["$legacyPolicyKeyPrefix${globalPolicy.id}"] = serializedLegacyPolicy
        redisTemplate.opsForSet().add(legacyGlobalPolicyIndexKey, globalPolicy.id)

        store.setPolicy(systemPolicy).block()

        store.getGlobalPolicies().block().orEmpty().assert().isEmpty()
        store.getPolicies(setOf(systemPolicy.id)).block().orEmpty().assert().containsExactly(systemPolicy)
    }

    @Test
    fun staleLegacyGlobalMembershipCannotPromoteANonGlobalPolicy() {
        val serializedPolicy = CoSecJsonSerializer.writeValueAsString(systemPolicy)
        redisTemplate.opsForValue()["$legacyPolicyKeyPrefix${systemPolicy.id}"] = serializedPolicy
        redisTemplate.opsForSet().add(legacyGlobalPolicyIndexKey, systemPolicy.id)

        store.getGlobalPolicies().block().orEmpty().assert().isEmpty()

        redisTemplate.opsForHash<String, String>()[policyStoreKey, systemPolicy.id]
            .assert().isEqualTo(serializedPolicy)
        redisTemplate.opsForHash<String, String>()[globalPolicyStoreKey, systemPolicy.id]
            .assert().isNull()
    }

    @Test
    fun globalReadOnlyUsesTheGlobalProjection() {
        val nonGlobalPolicies = (1..100).map { index ->
            policy(PolicyType.SYSTEM, id = "system-$index")
        }
        nonGlobalPolicies.forEach { store.setPolicy(it).block() }
        store.setPolicy(globalPolicy).block()

        store.getGlobalPolicies().block().orEmpty().assert().containsExactly(globalPolicy)
        redisTemplate.opsForHash<String, String>().size(policyStoreKey).assert().isEqualTo(101)
        redisTemplate.opsForHash<String, String>().size(globalPolicyStoreKey).assert().isEqualTo(1)
    }

    @Test
    fun policyStoreKeysMustUseTheSameRedisHashTag() {
        assertThrownBy<IllegalArgumentException> {
            RedisPolicyStore(
                redisTemplate,
                CoSecJsonSerializer,
                "{policy}:store",
                "{global}:store",
                legacyPolicyKeyPrefix,
                legacyGlobalPolicyIndexKey,
            )
        }.hasMessage("Policy store keys must use the same Redis hash tag.")
    }

    @Test
    fun blockingRedisWorkRunsOutsideTheNonBlockingSubscriber() {
        store.setPolicy(globalPolicy).block()
        val subscriberThread = AtomicReference<Thread>()
        val resultThread = AtomicReference<Thread>()

        Mono.defer {
            subscriberThread.set(Thread.currentThread())
            store.getPolicies(setOf(globalPolicy.id))
                .doOnNext { resultThread.set(Thread.currentThread()) }
        }.subscribeOn(Schedulers.parallel())
            .block()

        (subscriberThread.get() is NonBlocking).assert().isTrue()
        (resultThread.get() is NonBlocking).assert().isFalse()
    }

    @Test
    fun concurrentTypeUpdatesCannotSplitDocumentAndGlobalMembership() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val writes = (1..100).map { index ->
                executor.submit {
                    store.setPolicy(if (index % 2 == 0) globalPolicy else systemPolicy).block()
                }
            }
            writes.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val storedPolicy = store.getPolicies(setOf(globalPolicy.id)).block().orEmpty().single()
        val isGlobal = store.getGlobalPolicies().block().orEmpty().any { it.id == globalPolicy.id }
        isGlobal.assert().isEqualTo(storedPolicy.type == PolicyType.GLOBAL)
    }

    private fun policy(type: PolicyType, id: String = "policy"): PolicyData = PolicyData(
        id = id,
        category = "category",
        name = "name",
        description = "description",
        type = type,
        tenantId = "tenantId",
        statements = emptyList(),
    )
}
