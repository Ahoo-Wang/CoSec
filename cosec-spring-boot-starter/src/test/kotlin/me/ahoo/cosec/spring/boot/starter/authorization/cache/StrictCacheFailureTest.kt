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

import io.mockk.mockk
import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.boot.starter.CoCacheProperties
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.SetToSetCodecExecutor
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.GlobalPolicyIndexKeyConverter
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.cache.RedisAppRolePermissionRepository
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Policy and permission caches fail closed when Redis is unavailable.
 */
class StrictCacheFailureTest {
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var unreachableRedis: StringRedisTemplate

    @BeforeEach
    fun setup() {
        val clientConfiguration = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build()
        connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration("localhost", UNREACHABLE_PORT), clientConfiguration)
        connectionFactory.afterPropertiesSet()
        unreachableRedis = StringRedisTemplate(connectionFactory)
    }

    @AfterEach
    fun destroy() {
        connectionFactory.destroy()
    }

    @Test
    fun cosecDistributedCachesRethrowRedisFailures() {
        val objectMapper = JsonMapper.builder().build()
        val cocacheProperties = CoCacheProperties()
        val policyConfiguration = CoSecPolicyCacheAutoConfiguration(CacheProperties())
        val permissionConfiguration = CoSecPermissionCacheAutoConfiguration(CacheProperties())
        val distributedCaches: List<DistributedCache<*>> = listOf(
            policyConfiguration.policyCacheDistributedCache(unreachableRedis, objectMapper, cocacheProperties),
            policyConfiguration.globalPolicyIndexCacheDistributedCache(unreachableRedis, cocacheProperties),
            permissionConfiguration.appPermissionCacheDistributedCache(
                unreachableRedis,
                objectMapper,
                cocacheProperties
            ),
            permissionConfiguration.rolePermissionCacheDistributedCache(unreachableRedis, cocacheProperties),
        )
        distributedCaches.forEach { distributedCache ->
            assertThrows<DataAccessException> { distributedCache.getCache("key") }
        }
    }

    @Test
    fun degradedIndexSkipsGlobalDenyButStrictIndexDenies() {
        val principalAllow = PolicyData(
            "principal-allow",
            "category",
            "principal-allow",
            "",
            PolicyType.CUSTOM,
            "tenant",
            statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
        )
        // Redis is unreachable: degraded mode reads the global index as empty, so any global DENY is skipped
        // and the principal ALLOW decides; strict mode surfaces the failure and the pipeline denies.
        evaluate(strictFailure = false, principalAllow).assert().isEqualTo(SecurityStatus.ALLOWED)
        evaluate(strictFailure = true, principalAllow).assert().isEqualTo(SecurityStatus.ERROR)
    }

    private fun evaluate(strictFailure: Boolean, principalPolicy: Policy): SecurityStatus {
        val index = DefaultCoherentCache(
            CoherentCacheConfiguration<String, Set<String>>(
                cacheName = "GlobalPolicyIndexCache",
                clientId = "StrictCacheFailureTest",
                keyConverter = GlobalPolicyIndexKeyConverter("test:global:policy"),
                distributedCache = RedisDistributedCache(
                    unreachableRedis,
                    SetToSetCodecExecutor(unreachableRedis),
                    strictFailure
                ),
                clientSideCache = CaffeineClientSideCache.build(),
                cacheSource = CacheSource.noOp(),
                ttlPolicy = TtlPolicy(ttl = TtlAt.FOREVER),
            ),
            NoOpCacheEvictedEventBus,
        )
        val policies = DefaultCoherentCache(
            CoherentCacheConfiguration<String, Policy>(
                cacheName = "PolicyCache",
                clientId = "StrictCacheFailureTest",
                keyConverter = ToStringKeyConverter("test:policy:"),
                distributedCache = me.ahoo.cache.distributed.InMemoryDistributedCache(),
                clientSideCache = CaffeineClientSideCache.build(),
                cacheSource = CacheSource.noOp(),
                ttlPolicy = TtlPolicy(ttl = TtlAt.FOREVER),
            ),
            NoOpCacheEvictedEventBus,
        )
        policies[principalPolicy.id] = principalPolicy
        val repository = RedisPolicyRepository(TestIndexCache(index), TestPolicyCache(policies))
        val authorization = SimpleAuthorization(repository, RedisAppRolePermissionRepository(mockk(), mockk()))
        val principal = SimplePrincipal("user", policies = setOf(principalPolicy.id))
        val pipeline = SecurityPipeline(
            object : RequestParser<String> {
                override fun parse(request: String): Request = EvaluateRequest(path = request)
            },
            SecurityContextParser { SimpleSecurityContext(principal) },
            authorization,
        )
        try {
            return pipeline.evaluate("/orders/1").block()!!.status
        } finally {
            index.close()
            policies.close()
        }
    }

    private class TestIndexCache(cache: CoherentCache<String, Set<String>>) :
        GlobalPolicyIndexCache,
        CoherentCache<String, Set<String>> by cache

    private class TestPolicyCache(cache: CoherentCache<String, Policy>) :
        PolicyCache,
        CoherentCache<String, Policy> by cache

    companion object {
        private const val UNREACHABLE_PORT = 1
    }
}
