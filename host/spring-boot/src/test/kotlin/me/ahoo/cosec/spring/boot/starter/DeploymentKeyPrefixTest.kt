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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.MembershipLoader
import me.ahoo.cosec.cache.RedisApiKeyReader
import me.ahoo.cosec.cache.RedisApiKeyStore
import me.ahoo.cosec.cache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.ApiKeyStore
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spring.boot.starter.apikey.CoSecApiKeyAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authentication.social.CoSecSocialAuthenticationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authentication.social.SocialAuthenticationProperties
import me.ahoo.cosec.spring.boot.starter.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecMembershipAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

/**
 * `SNP-9` (RC1-46): every key a deployment writes to Redis lives under its one `cosec.key-prefix`, so two deployments
 * on one Redis that differ in it share nothing: no API key one issues is accepted by the other, no membership one
 * caches is served by the other, and their rate limit counters, revocations and documents stay apart.
 *
 * The CoCache families are enumerated, not listed: every cache the runtime registered, whichever configuration made
 * it, must be in a namespace under the prefix. The families that write Redis directly are checked one by one: the API
 * key store and reader, the rate limiter and the social login's OAuth states.
 */
class DeploymentKeyPrefixTest {
    private val prefix = "deployment-${UUID.randomUUID()}"

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                // Lazy: the templates are created, and nothing connects.
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecMembershipAutoConfiguration::class.java,
                CoSecApiKeyAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cosec.key-prefix=$prefix",
            "cocache.backend=memory",
            "cosec.authorization.snapshot.eviction-check=warn",
            "cosec.api-key.enabled=true",
            "cosec.api-key.store=redis",
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
        .withBean(MembershipLoader::class.java, {
            MembershipLoader { _, _ ->
                MembershipAnswer.Known(Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
            }
        })

    @Test
    fun `every key family the starter wires lives under cosec key-prefix`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val runtime = context.getBean(CacheRuntime::class.java)
            // The snapshot, control, candidate and shadow caches, the revocations and the memberships at least.
            runtime.cacheNames.size.assert().isGreaterThanOrEqualTo(MINIMUM_CACHES)
            runtime.cacheNames.forEach { name ->
                checkNotNull(
                    runtime.admin(name)
                ).inspect("k").storageKey.assert().describedAs(name).startsWith("$prefix:")
            }
            context.getBean(ApiKeyStore::class.java).assert().isInstanceOf(RedisApiKeyStore::class.java)
            context.getBean(ApiKeyStore::class.java).field<String>("keyPrefix").assert().isEqualTo("$prefix:api-key")
            context.getBean(ApiKeyReader::class.java).assert().isInstanceOf(RedisApiKeyReader::class.java)
            context.getBean(ApiKeyReader::class.java).field<String>("keyPrefix").assert().isEqualTo("$prefix:api-key")
            context.getBean(RateLimiter::class.java).assert().isInstanceOf(ReactiveRedisRateLimiter::class.java)
            context.getBean(
                RateLimiter::class.java
            ).field<String>("keyPrefix").assert().isEqualTo("$prefix:rate-limiter")
        }
        // The social login's OAuth states, whose configuration needs a login provider to run.
        val states = CoSecSocialAuthenticationAutoConfiguration(SocialAuthenticationProperties())
            .authStateCache(StringRedisTemplate(), CoSecProperties(keyPrefix = "$prefix:"))
        states.field<String>("keyPrefix").assert().isEqualTo("$prefix:oauth:state:")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.field(name: String): T {
        val field = generateSequence<Class<*>>(javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { it.name == name }
        field.isAccessible = true
        return field.get(this) as T
    }

    private companion object {
        const val MINIMUM_CACHES = 6
    }
}
