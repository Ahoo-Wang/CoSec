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

package me.ahoo.cosec.spring.boot.starter.apikey

import io.mockk.mockk
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.apikey.ApiKeySecurityContextParser
import me.ahoo.cosec.apikey.ApiKeyStore
import me.ahoo.cosec.apikey.ApiKeys
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.RedisApiKeyStore
import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.securityContextParseScheduler
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

internal class CoSecApiKeyAutoConfigurationTest {
    private val key = ApiKeys.generate()

    private val contextRunner = ApplicationContextRunner()
        .withBean(PolicyRepository::class.java, {
            object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(emptyList())
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            }
        })
        .withBean(AppRolePermissionRepository::class.java, {
            object : AppRolePermissionRepository {
                override fun getAppRolePermission(
                    appId: String,
                    spaceId: String,
                    roleIds: Set<RoleId>
                ): Mono<AppRolePermission> = Mono.empty()
            }
        })
        .withBean(TokenVerifier::class.java, { mockk() })
        .withUserConfiguration(
            CoSecAutoConfiguration::class.java,
            CoSecApiKeyAutoConfiguration::class.java,
            CoSecRequestParserAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
        )

    @Test
    fun disabledByDefault() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(ApiKeyStore::class.java)
            assertThat(
                context.getBean(SecurityContextParser::class.java)
            ).isInstanceOf(DefaultSecurityContextParser::class.java)
            context.environment.securityContextParseScheduler().assert().isSameAs(Schedulers.immediate())
        }
    }

    @Test
    fun resolvesConfiguredKeys() {
        contextRunner
            .withPropertyValues(
                "cosec.api-key.enabled=true",
                "cosec.api-key.header=X-Service-Key",
                "cosec.api-key.keys[0].id=billing",
                "cosec.api-key.keys[0].hash=${ApiKeys.hash(key)}",
                "cosec.api-key.keys[0].principal-id=svc-billing",
                "cosec.api-key.keys[0].roles=billing,reader",
                "cosec.api-key.keys[0].expires-at=2999-01-01T00:00:00Z",
            )
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(ApiKeyStore::class.java)
                val parser = context.getBean(SecurityContextParser::class.java)
                assertThat(parser).isInstanceOf(ApiKeySecurityContextParser::class.java)
                val principal = parser.parse(EvaluateRequest(headers = mapOf("X-Service-Key" to key))).principal
                principal.id.assert().isEqualTo("svc-billing")
                principal.roles.assert().containsExactlyInAnyOrder("billing", "reader")
            }
    }

    @Test
    fun blockingStoreParsesOffTheEventLoop() {
        contextRunner
            .withPropertyValues("cosec.api-key.enabled=true", "cosec.api-key.store-blocking=true")
            .run { context ->
                context.environment.securityContextParseScheduler().assert().isSameAs(Schedulers.boundedElastic())
            }
    }

    @Test
    fun rejectsPlainKeysInConfiguration() {
        contextRunner
            .withPropertyValues(
                "cosec.api-key.enabled=true",
                "cosec.api-key.keys[0].id=bad",
                "cosec.api-key.keys[0].hash=$key",
                "cosec.api-key.keys[0].principal-id=svc",
            )
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun redisStore() {
        contextRunner
            .withUserConfiguration(DataRedisAutoConfiguration::class.java)
            .withPropertyValues("cosec.api-key.enabled=true", "cosec.api-key.store=redis")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ApiKeyStore::class.java)).isInstanceOf(RedisApiKeyStore::class.java)
                context.environment.securityContextParseScheduler().assert().isSameAs(Schedulers.boundedElastic())
            }
    }
}
