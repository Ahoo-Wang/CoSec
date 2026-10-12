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

package me.ahoo.cosec.spring.boot.starter.micrometer

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.limiter.RateLimitingAuthorization
import me.ahoo.cosec.micrometer.MeteredAuthorization
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.limiter.CoSecRateLimitAutoConfiguration
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

internal class CoSecMetricsAutoConfigurationTest {
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
            CoSecMetricsAutoConfiguration::class.java,
            CoSecRateLimitAutoConfiguration::class.java,
            CoSecRequestParserAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
        )

    @Test
    fun metersOutsideRateLimiting() {
        contextRunner
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(MetricsProperties::class.java)
                val authorization = context.getBean(Authorization::class.java)
                assertThat(authorization).isInstanceOf(MeteredAuthorization::class.java)
                assertThat(
                    (authorization as MeteredAuthorization).delegate
                ).isInstanceOf(RateLimitingAuthorization::class.java)
                authorization.authorize(EvaluateRequest(), SimpleSecurityContext(SimplePrincipal("user"))).block()
                context.getBean(MeterRegistry::class.java).find(MeteredAuthorization.METRIC_NAME).timer()?.count()
                    .assert().isEqualTo(1)
            }
    }

    @Test
    fun withoutMeterRegistry() {
        contextRunner.run { context ->
            assertThat(context.getBean(Authorization::class.java)).isInstanceOf(RateLimitingAuthorization::class.java)
        }
    }

    @Test
    fun disabled() {
        contextRunner
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues("cosec.metrics.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(MetricsProperties::class.java)
                assertThat(
                    context.getBean(Authorization::class.java)
                ).isInstanceOf(RateLimitingAuthorization::class.java)
            }
    }
}
