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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.cache.AppPermissionCache
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.cache.RedisAppRolePermissionRepository
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.cache.RevokedTokenCache
import me.ahoo.cosec.cache.RolePermissionCache
import me.ahoo.cosec.cache.createCache
import me.ahoo.cosec.cache.createGlobalPolicyIndex
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
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.spring.boot.starter.AuthorizationImpl
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Policy and permission caches fail closed when Redis is unavailable, whatever the CoCache properties say.
 */
class StrictCacheFailureTest {

    @Test
    fun cosecStoreOfRecordCachesRethrowRemoteFailuresDespiteDegradeDefaults() {
        val backend = HookedBackend(InMemoryBackend())
        ApplicationContextRunner()
            .withPropertyValues(
                "cocache.backend=memory",
                // an application's defaults must not weaken CoSec's store-of-record caches
                "cocache.defaults.read-failure=degrade",
                "cocache.defaults.ttl=60s",
                "cocache.defaults.foreign-format=reclaim",
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "${JwtProperties.PREFIX}.token-revocation.enabled=true",
            )
            .withBean(CoherenceBackend::class.java, { backend })
            .withUserConfiguration(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPolicyCacheAutoConfiguration::class.java,
                CoSecPermissionCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val policyCache = context.getBean(PolicyCache::class.java)
                val index = context.getBean(GlobalPolicyIndexCache::class.java)
                val appPermissionCache = context.getBean(AppPermissionCache::class.java)
                val rolePermissionCache = context.getBean(RolePermissionCache::class.java)

                val runtime = context.getBean(CacheRuntime::class.java)
                policyCache.put("p", PolicyData("p", "c", "n", "", PolicyType.GLOBAL, "t"))
                // never expires in Redis, despite cocache.defaults.ttl
                checkNotNull(runtime.admin(CoSecCacheSpecs.POLICY_CACHE_NAME)).inspect("p").remote.ttl.assert().isNull()

                backend.failAll = true
                assertThrows<CacheReadException> { policyCache["p"] }
                // a SetCache read has no source to degrade to: it always rethrows the backend's error
                assertThrows<RuntimeException> { index[GlobalPolicyIndexCache.CACHE_KEY] }
                assertThrows<CacheReadException> { appPermissionCache["app"] }
                assertThrows<CacheReadException> { rolePermissionCache[AppSpacedRoleId("app", "role")] }
                // the revocation check degrades on purpose (documented): a miss means "not revoked"
                context.getBean(RevokedTokenCache::class.java)["jti"].assert().isNull()
            }
    }

    @ParameterizedTest
    @EnumSource(AuthorizationImpl::class)
    fun unreadableGlobalIndexDeniesEvenWithDegradedReads(impl: AuthorizationImpl) {
        // Redis is unavailable. With CoCache 5, a degraded read saw the global index as empty, so any global DENY was
        // skipped and the principal ALLOW decided. A CoCache 6 SetCache never degrades a read: the pipeline denies.
        evaluate(impl, strict = false).assert().isEqualTo(SecurityStatus.ERROR)
        evaluate(impl, strict = true).assert().isEqualTo(SecurityStatus.ERROR)
    }

    private fun evaluate(impl: AuthorizationImpl, strict: Boolean): SecurityStatus {
        val principalAllow = PolicyData(
            "principal-allow",
            "category",
            "principal-allow",
            "",
            PolicyType.CUSTOM,
            "tenant",
            statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
        )
        val failing = HookedBackend(InMemoryBackend()).apply { failAll = true }
        val indexRuntime = CacheRuntime.builder().backend(failing).build()
        val policyRuntime = CacheRuntime.builder().backend(InMemoryBackend()).build()
        try {
            var indexSpec = CoSecCacheSpecs.globalPolicyIndex()
            if (!strict) {
                indexSpec = indexSpec.toBuilder().updatePolicy { it.readFailure(ReadFailureMode.DEGRADE) }.build()
            }
            val index = indexRuntime.createGlobalPolicyIndex(indexSpec)
            val policies: PolicyCache = policyRuntime.createCache(
                CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer))
            )
            policies.put(principalAllow.id, principalAllow)
            val authorization = impl.create(
                RedisPolicyRepository(index, policies),
                RedisAppRolePermissionRepository(
                    policyRuntime.createCache(
                        CoSecCacheSpecs.appPermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer))
                    ),
                    policyRuntime.createCache(
                        CoSecCacheSpecs.rolePermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer))
                    ),
                ),
            )
            val principal = SimplePrincipal("user", policies = setOf(principalAllow.id))
            val pipeline = SecurityPipeline(
                object : RequestParser<String> {
                    override fun parse(request: String): Request = EvaluateRequest(path = request)
                },
                SecurityContextParser { SimpleSecurityContext(principal) },
                authorization,
            )
            return pipeline.evaluate("/orders/1").block()!!.status
        } finally {
            indexRuntime.close()
            policyRuntime.close()
        }
    }
}
