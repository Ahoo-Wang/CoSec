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

package me.ahoo.cosec.spring.boot.starter.explain

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.explain.ExplainLayer
import me.ahoo.cosec.explain.ExplainWarningCode
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.limiter.CoSecRateLimitAutoConfiguration
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

@ParameterizedClass
@EnumSource(ExplainerImpl::class)
internal class CoSecExplainAutoConfigurationTest(private val impl: ExplainerImpl) {
    private val policyRepository = object : PolicyRepository {
        private val policy = PolicyData(
            id = "orders",
            category = "",
            name = "orders",
            description = "",
            type = PolicyType.GLOBAL,
            tenantId = "tenant",
            statements = listOf(
                StatementData(
                    name = "read",
                    effect = Effect.ALLOW,
                    action = PathActionMatcherFactory.INSTANCE.create("/orders/**".asConfiguration()),
                ),
            ),
        )

        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(policy))
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
        .withBean(TokenVerifier::class.java, {
            mockk<TokenVerifier> { every { toPrincipal(any()) } throws TokenExpiredException() }
        })
        .withUserConfiguration(
            CoSecAutoConfiguration::class.java,
            CoSecRateLimitAutoConfiguration::class.java,
            CoSecRequestParserAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
            *listOfNotNull(
                TraceExplainerConfiguration::class.java.takeIf { impl == ExplainerImpl.TRACE }
            ).toTypedArray(),
            CoSecExplainAutoConfiguration::class.java,
        )

    @Test
    fun disabledByDefault() {
        contextRunner.run { context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AuthorizationExplainer::class.java)
                .doesNotHaveBean(CoSecExplainEndpoint::class.java)
        }
    }

    @Test
    fun explainsWithTheExplainerUnderTest() {
        contextRunner
            .withPropertyValues("cosec.explain.enabled=true")
            .run { context ->
                val expected = if (impl == ExplainerImpl.TRACE) "traceAuthorizationExplainer" else "authorizationExplainer"
                context.getBeanNamesForType(AuthorizationExplainer::class.java).assert().containsExactly(expected)
            }
    }

    @Test
    fun endpointExplains() {
        contextRunner
            .withPropertyValues("cosec.explain.enabled=true")
            .run { context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ExplainProperties::class.java)
                    .hasSingleBean(AuthorizationExplainer::class.java)
                    .hasSingleBean(CoSecExplainEndpoint::class.java)
                val endpoint = context.getBean(CoSecExplainEndpoint::class.java)
                val allowed = checkNotNull(
                    endpoint.explain(
                        principal = mapOf("id" to "user", "tenantId" to "tenant", "roles" to listOf("clerk")),
                        request = mapOf("path" to "/orders/1", "method" to "GET", "appId" to "app"),
                        exhaustive = null,
                        detail = "full",
                    ),
                )
                allowed.status.assert().isEqualTo(SecurityStatus.ALLOWED)
                allowed.decidedBy?.layer.assert().isEqualTo(ExplainLayer.GLOBAL_POLICY)
                allowed.decidedBy?.policyId.assert().isEqualTo("orders")
                allowed.layers.single { it.layer == ExplainLayer.GLOBAL_POLICY }
                    .policies.single().statements.single().action.configuration.assert().isNotNull()

                val denied = checkNotNull(
                    endpoint.explain(
                        mapOf("id" to "user"),
                        mapOf("path" to "/users"),
                        exhaustive = true,
                        detail = null
                    ),
                )
                denied.status.assert().isEqualTo(SecurityStatus.FORBIDDEN)
                denied.decidedBy.assert().isNull()
            }
    }

    @Test
    fun endpointExplainsTheRequestsToken() {
        contextRunner
            .withPropertyValues("cosec.explain.enabled=true")
            .run { context ->
                val endpoint = context.getBean(CoSecExplainEndpoint::class.java)
                val anonymous = checkNotNull(endpoint.explain(null, mapOf("path" to "/orders/1"), null, null))
                anonymous.status.assert().isEqualTo(SecurityStatus.ALLOWED)
                anonymous.warnings.assert().isEmpty()
                val expired = checkNotNull(
                    endpoint.explain(
                        principal = null,
                        request = mapOf("path" to "/users", "headers" to mapOf("Authorization" to "Bearer expired")),
                        exhaustive = null,
                        detail = null,
                    ),
                )
                expired.status.assert().isEqualTo(SecurityStatus.UNAUTHORIZED)
                expired.reason.assert().isEqualTo("Token Expired")
                expired.warnings.single().code.assert().isEqualTo(ExplainWarningCode.TOKEN_INVALID)
            }
    }
}
