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

import io.mockk.mockk
import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.SetElementsCache
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.distributed.SetElementsStore
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.LocalCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.RedisSetDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import reactor.kotlin.test.test

/**
 * A publish that fails halfway, against Redis: one of the two writes of [RedisPolicyRepository.setPolicy] fails.
 * Whatever the writer and a fresh instance read afterwards, the policy never becomes global unless it was published
 * as global and its content was written (see [PolicyPublishAtomicityTest] for why this is not full atomicity).
 */
class PolicyPublishFailureIntegrationTest {
    private class IndexCache(cache: DefaultCoherentCache<String, Set<String>>) :
        GlobalPolicyIndexCache,
        CoherentCache<String, Set<String>> by cache,
        @Suppress("UNCHECKED_CAST")
        SetElementsCache<String, String> by (cache as SetElementsCache<String, String>)

    private class TestPolicyCache(cache: CoherentCache<String, Policy>) :
        PolicyCache,
        CoherentCache<String, Policy> by cache

    /** Redis policy storage whose writes fail while [failing] is set, as when Redis drops the connection. */
    private class BreakablePolicyStore(private val delegate: DistributedCache<Policy>) :
        DistributedCache<Policy> by delegate {
        @Volatile
        var failing = false

        override fun setCache(key: String, value: CacheValue<Policy>) {
            check(!failing) { "Redis unavailable" }
            delegate.setCache(key, value)
        }
    }

    /** The Redis global policy index, whose element updates fail while [failing] is set. */
    private class BreakableIndexStore(private val delegate: RedisSetDistributedCache) :
        DistributedCache<Set<String>> by delegate,
        SetElementsStore<String> by delegate {
        @Volatile
        var failing = false

        override fun addElements(key: String, elements: Collection<String>, ttlAt: Long) {
            check(!failing) { "Redis unavailable" }
            delegate.addElements(key, elements, ttlAt)
        }

        override fun removeElements(key: String, elements: Collection<String>, ttlAt: Long) {
            check(!failing) { "Redis unavailable" }
            delegate.removeElements(key, elements, ttlAt)
        }

        override fun close() = delegate.close()
    }

    private lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var keyPrefix: String
    private val eventBus: CacheEvictedEventBus = LocalCacheEvictedEventBus()
    private val caches = mutableListOf<CoherentCache<*, *>>()
    private lateinit var policyStore: BreakablePolicyStore
    private lateinit var indexStore: BreakableIndexStore

    @BeforeEach
    fun setup() {
        lettuceConnectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
        lettuceConnectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        keyPrefix = "test:publish:" + MockIdGenerator.INSTANCE.generateAsString() + ":"
        policyStore = BreakablePolicyStore(policyStore())
        indexStore = BreakableIndexStore(RedisSetDistributedCache(redisTemplate))
    }

    @AfterEach
    fun destroy() {
        caches.forEach { it.close() }
        redisTemplate.keys("$keyPrefix*").takeIf { it.isNotEmpty() }?.let { redisTemplate.delete(it) }
        lettuceConnectionFactory.destroy()
    }

    private fun policyStore(): DistributedCache<Policy> = RedisDistributedCache(
        redisTemplate,
        ObjectToJsonCodecExecutor(Policy::class.java, redisTemplate, CoSecJsonSerializer),
    )

    private fun <V> coherentCache(
        name: String,
        clientId: String,
        distributedCache: DistributedCache<V>,
        keyConverter: KeyConverter<String>
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

    /** One CoSec instance with its own local caches over the shared Redis keys. */
    private fun instance(
        clientId: String,
        policies: DistributedCache<Policy>,
        index: DistributedCache<Set<String>>
    ): RedisPolicyRepository {
        val indexCache =
            coherentCache("GlobalPolicyIndexCache", clientId, index, GlobalPolicyIndexKeyConverter(keyPrefix + "index"))
        val policyCache = coherentCache("PolicyCache", clientId, policies, ToStringKeyConverter(keyPrefix + "policy:"))
        return RedisPolicyRepository(IndexCache(indexCache), TestPolicyCache(policyCache))
    }

    private fun writer() = instance("writer", policyStore, indexStore)

    /** A fresh instance, started after the failure: it sees only what Redis holds. */
    private fun reader() = instance("reader", policyStore(), RedisSetDistributedCache(redisTemplate))

    private fun broadAllow(type: PolicyType) = PolicyData(
        id = "broad-allow",
        category = "",
        name = "broad-allow",
        description = "",
        type = type,
        tenantId = "",
        statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
    )

    private fun indexMembers(): Set<String> = redisTemplate.opsForSet().members(keyPrefix + "index").orEmpty()

    /** What an unrelated principal, who does not list the policy, may do. */
    private fun anyoneElse(repository: RedisPolicyRepository): AuthorizeResult? =
        // bob has no roles, so app permissions are never read
        SimpleAuthorization(repository, mockk())
            .authorize(
                EvaluateRequest(path = "/orders/1", method = "DELETE"),
                SimpleSecurityContext(SimplePrincipal("bob"))
            )
            .block()

    private fun failingPublish(repository: RedisPolicyRepository, policy: Policy) {
        repository.setPolicy(policy).test().expectError(IllegalStateException::class.java).verify()
    }

    @Test
    fun demotionWhoseContentWriteFailsIsNoLongerGlobal() {
        val writer = writer()
        writer.setPolicy(broadAllow(PolicyType.GLOBAL)).block()
        anyoneElse(writer).assert().isEqualTo(AuthorizeResult.ALLOW)

        policyStore.failing = true
        failingPublish(writer, broadAllow(PolicyType.CUSTOM))

        indexMembers().assert().isEmpty()
        anyoneElse(writer).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        anyoneElse(reader()).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun demotionWhoseIndexUpdateFailsChangesNothing() {
        val writer = writer()
        writer.setPolicy(broadAllow(PolicyType.GLOBAL)).block()

        indexStore.failing = true
        failingPublish(writer, broadAllow(PolicyType.CUSTOM))

        // The policy is still the global one it was before the publish: content and index agree.
        indexMembers().assert().containsExactly("broad-allow")
        reader().getPolicies(setOf("broad-allow")).block()!!.single().type.assert().isEqualTo(PolicyType.GLOBAL)
    }

    @Test
    fun promotionWhoseIndexUpdateFailsStaysOutOfTheIndex() {
        val writer = writer()
        writer.setPolicy(broadAllow(PolicyType.CUSTOM)).block()

        indexStore.failing = true
        failingPublish(writer, broadAllow(PolicyType.GLOBAL))

        indexMembers().assert().isEmpty()
        anyoneElse(writer).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        anyoneElse(reader()).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)

        // Retrying the publish completes it.
        indexStore.failing = false
        writer.setPolicy(broadAllow(PolicyType.GLOBAL)).block()
        indexMembers().assert().containsExactly("broad-allow")
        anyoneElse(reader()).assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun promotionWhoseContentWriteFailsIsNeverIndexed() {
        val writer = writer()
        policyStore.failing = true
        failingPublish(writer, broadAllow(PolicyType.GLOBAL))

        indexMembers().assert().isEmpty()
        anyoneElse(reader()).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }
}
