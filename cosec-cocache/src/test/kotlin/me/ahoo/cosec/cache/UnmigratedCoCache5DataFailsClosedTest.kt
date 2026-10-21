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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * A 7.0 instance started on CoSec 6.x data that was never migrated, against real Redis. Every read of CoCache 5
 * content fails (`ForeignFormatMode.FAIL`), leaves Redis as it was, and the pipeline denies with `500`: the global
 * deny can never be skipped, and no request is ever allowed.
 */
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class UnmigratedCoCache5DataFailsClosedTest(private val impl: AuthorizationImpl) {
    private val connectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(connectionFactory)
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val data = CoCache5DataFixture(redis, keyPrefix).apply { write() }
    private val backend = CoSecTestCaches.redisBackend()
    private val caches = CoSecTestCaches(backend, keyPrefix)

    @AfterEach
    fun destroy() {
        caches.close()
        backend.close()
        redis.delete(redis.keys("$keyPrefix*"))
        connectionFactory.destroy()
    }

    private fun assertForeign(read: () -> Any?) {
        val error = assertThrows<CacheReadException> { read() }
        error.cause.assert().isInstanceOf(ForeignFormatException::class.java)
    }

    @Test
    fun readsOfCoCache5ContentFailAndKeepIt() {
        assertForeign { caches.policyCache["custom-allow"] }
        assertForeign { caches.policyCache["missing"] }
        assertForeign { caches.globalPolicyIndexCache[GlobalPolicyIndexCache.CACHE_KEY] }
        assertForeign { caches.appPermissionCache["app"] }
        assertThrows<CacheReadException> { caches.policyRepository().getGlobalPolicy().block() }
        assertThrows<CacheReadException> { caches.policyRepository().getPolicies(setOf("custom-allow")).block() }

        // nothing was reclaimed: the only copy is still there for the migration
        redis.opsForSet().members("$keyPrefix:global:policy").assert().isEqualTo(setOf("global-deny"))
        redis.opsForValue().get("$keyPrefix:policy:custom-allow").assert().isNotNull()
    }

    @Test
    fun revokedTokenIsNeverReclaimed() {
        // as with cocache.defaults.foreign-format=reclaim: a reclaim would delete the 6.x revocation
        val reclaiming = CachePolicy.DEFAULT.toBuilder().foreignFormat(ForeignFormatMode.RECLAIM).build()
        val revokedTokenCache: RevokedTokenCache = caches.runtime.createCache(
            CoSecCacheSpecs.revokedToken(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix, reclaiming)
        )

        assertForeign { revokedTokenCache["jti"] }
        assertThrows<CacheReadException> { CoCacheTokenStore(revokedTokenCache).isRevoked("jti") }
        redis.opsForValue().get("$keyPrefix:token:revoked:jti").assert().isEqualTo("true")
    }

    @Test
    fun requestsAreNeverAllowed() {
        data.customAllow.type.assert().isEqualTo(PolicyType.CUSTOM)
        val principal = SimplePrincipal("alice", policies = setOf(data.customAllow.id), roles = setOf("admin"))
        val pipeline = SecurityPipeline(
            object : RequestParser<String> {
                override fun parse(request: String): Request =
                    EvaluateRequest(path = request, headers = mapOf("CoSec-App-Id" to "app"))
            },
            SecurityContextParser { SimpleSecurityContext(principal) },
            impl.create(caches.policyRepository(), caches.appRolePermissionRepository()),
        )
        repeat(2) {
            pipeline.evaluate("/orders/1").block()!!.status.assert().isEqualTo(SecurityStatus.ERROR)
        }
    }
}
