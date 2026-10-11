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

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.SetElementsCache
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.LocalCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cache.spring.redis.RedisSetDistributedCache
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

/**
 * Two independent CoSec instances update the global policy index concurrently against one Redis.
 * The previous read-modify-write lost entries under this load; atomic element updates must not.
 */
class GlobalPolicyIndexConcurrencyIntegrationTest {
    private class IndexCache(cache: DefaultCoherentCache<String, Set<String>>) :
        GlobalPolicyIndexCache,
        CoherentCache<String, Set<String>> by cache,
        @Suppress("UNCHECKED_CAST")
        SetElementsCache<String, String> by (cache as SetElementsCache<String, String>)

    private class TestPolicyCache(cache: CoherentCache<String, Policy>) :
        PolicyCache,
        CoherentCache<String, Policy> by cache

    private lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var indexKey: String
    private val caches = mutableListOf<CoherentCache<*, *>>()

    @BeforeEach
    fun setup() {
        lettuceConnectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
        lettuceConnectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        indexKey = "test:global:policy:" + MockIdGenerator.INSTANCE.generateAsString()
    }

    @AfterEach
    fun destroy() {
        caches.forEach { it.close() }
        redisTemplate.delete(indexKey)
        lettuceConnectionFactory.destroy()
    }

    private fun <V> coherentCache(
        name: String,
        clientId: String,
        distributedCache: DistributedCache<V>,
        eventBus: CacheEvictedEventBus,
        keyConverter: me.ahoo.cache.api.converter.KeyConverter<String> = ToStringKeyConverter("test:$name:")
    ): DefaultCoherentCache<String, V> = DefaultCoherentCache(
        CoherentCacheConfiguration(
            cacheName = name,
            clientId = clientId,
            keyConverter = keyConverter,
            distributedCache = distributedCache,
            clientSideCache = CaffeineClientSideCache.build(),
            cacheSource = CacheSource.noOp(),
            ttlPolicy = TtlPolicy(ttl = TtlAt.FOREVER),
        ),
        eventBus,
    ).also { caches.add(it) }

    /** One CoSec instance: its own local caches, sharing Redis (index), policy storage and the eviction bus. */
    private fun instance(
        clientId: String,
        policyStore: InMemoryDistributedCache<Policy>,
        eventBus: CacheEvictedEventBus
    ): RedisPolicyRepository {
        val indexCache = coherentCache(
            "GlobalPolicyIndexCache",
            clientId,
            RedisSetDistributedCache(redisTemplate),
            eventBus,
            GlobalPolicyIndexKeyConverter(indexKey),
        )
        val policyCache = coherentCache("PolicyCache", clientId, policyStore, eventBus)
        return RedisPolicyRepository(IndexCache(indexCache), TestPolicyCache(policyCache))
    }

    private fun policy(id: String, type: PolicyType) = PolicyData(id, "category", id, "", type, "tenant")

    @Test
    fun concurrentWritersOnTwoInstancesLoseNoEntries() {
        val policyStore = InMemoryDistributedCache<Policy>()
        val eventBus = LocalCacheEvictedEventBus()
        val instances =
            listOf(instance("instance-a", policyStore, eventBus), instance("instance-b", policyStore, eventBus))
        val policyIds = (1..64).map { "policy-$it" }

        Flux.fromIterable(policyIds.withIndex())
            .flatMap({ (index, policyId) ->
                instances[index % 2].setPolicy(
                    policy(policyId, PolicyType.GLOBAL)
                ).subscribeOn(Schedulers.boundedElastic())
            }, 16)
            .then()
            .block()

        redisTemplate.opsForSet().members(indexKey).assert().isEqualTo(policyIds.toSet())
        // FOREVER policy: the index key must not carry an expiry
        redisTemplate.getExpire(indexKey).assert().isEqualTo(-1L)
        instances.forEach { repository ->
            repository.getGlobalPolicy().block()!!.map { it.id }.toSet().assert().isEqualTo(policyIds.toSet())
        }

        val demoted = policyIds.filterIndexed { index, _ -> index % 3 == 0 }
        Flux.fromIterable(demoted.withIndex())
            .flatMap({ (index, policyId) ->
                instances[index % 2].setPolicy(
                    policy(policyId, PolicyType.CUSTOM)
                ).subscribeOn(Schedulers.boundedElastic())
            }, 16)
            .then()
            .block()

        val remaining = policyIds.toSet() - demoted.toSet()
        redisTemplate.opsForSet().members(indexKey).assert().isEqualTo(remaining)
        instances.forEach { repository ->
            repository.getGlobalPolicy().block()!!.map { it.id }.toSet().assert().isEqualTo(remaining)
        }
    }

    @Test
    fun addOntoCachedMissingIndexKeepsKeyPersistent() {
        val repository = instance("instance-a", InMemoryDistributedCache(), LocalCacheEvictedEventBus())
        // a read before any global policy exists caches a negative entry for the index key
        repository.getGlobalPolicy().block().assert().isEqualTo(emptyList<Policy>())

        repository.setPolicy(policy("policy-1", PolicyType.GLOBAL)).block()

        redisTemplate.opsForSet().members(indexKey).assert().isEqualTo(setOf("policy-1"))
        redisTemplate.getExpire(indexKey).assert().isEqualTo(-1L)
        repository.getGlobalPolicy().block()!!.map { it.id }.assert().isEqualTo(listOf("policy-1"))
    }
}
