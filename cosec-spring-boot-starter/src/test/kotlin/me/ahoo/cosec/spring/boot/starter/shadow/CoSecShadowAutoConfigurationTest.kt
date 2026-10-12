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

package me.ahoo.cosec.spring.boot.starter.shadow

import io.mockk.mockk
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.limiter.RateLimitingAuthorization
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.shadow.ShadowAuthorization
import me.ahoo.cosec.shadow.ShadowDiff
import me.ahoo.cosec.shadow.ShadowDiffKind
import me.ahoo.cosec.shadow.ShadowEventSink
import me.ahoo.cosec.shadow.ShadowPolicyProvider
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
import java.util.concurrent.CopyOnWriteArrayList

internal class CoSecShadowAutoConfigurationTest {
    private val diffs = CopyOnWriteArrayList<ShadowDiff>()

    private val policyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(emptyList())
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
        override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
    }

    private val appRolePermissionRepository = object : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: String,
            spaceId: String,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> = Mono.empty()
    }

    private val contextRunner = ApplicationContextRunner()
        .withBean(PolicyRepository::class.java, { policyRepository })
        .withBean(AppRolePermissionRepository::class.java, { appRolePermissionRepository })
        .withBean(TokenVerifier::class.java, { mockk() })
        .withUserConfiguration(
            CoSecAutoConfiguration::class.java,
            CoSecShadowAutoConfiguration::class.java,
            CoSecRateLimitAutoConfiguration::class.java,
            CoSecRequestParserAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
        )

    @Test
    fun disabledByDefault() {
        contextRunner.run { context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ShadowPolicyProvider::class.java)
            val authorization = context.getBean(Authorization::class.java) as RateLimitingAuthorization
            assertThat(authorization.delegate).isInstanceOf(SimpleAuthorization::class.java)
        }
    }

    @Test
    fun shadowsCandidatesInsideRateLimiting() {
        contextRunner
            .withPropertyValues(
                "cosec.shadow.enabled=true",
                "cosec.shadow.locations=classpath:cosec-shadow/*-policy.json",
                "cosec.shadow.sample-rate=1.0",
            )
            .withBean(ShadowEventSink::class.java, { ShadowEventSink(diffs::add) })
            .run { context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ShadowProperties::class.java)
                    .hasSingleBean(ShadowPolicyLoaderLifecycle::class.java)
                context.getBean(ShadowPolicyProvider::class.java).getPolicies().single().id.assert().isEqualTo("orders")
                context.getBean(
                    ShadowPolicyProvider::class.java
                ).getAppPermissions().single().id.assert().isEqualTo("shop")
                val authorization = context.getBean(Authorization::class.java) as RateLimitingAuthorization
                assertThat(authorization.delegate).isInstanceOf(ShadowAuthorization::class.java)
                authorization.authorize(
                    EvaluateRequest(path = "/orders/1"),
                    SimpleSecurityContext(SimplePrincipal("user"))
                )
                    .block().assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
                diffs.single().kind.assert().isEqualTo(ShadowDiffKind.WIDENED)
            }
    }

    @Test
    fun invalidCandidateFailsStartup() {
        contextRunner
            .withPropertyValues(
                "cosec.shadow.enabled=true",
                "cosec.shadow.locations=classpath:cosec-shadow/invalid/*.json"
            )
            .run { context -> assertThat(context).hasFailed() }
    }
}
