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

package me.ahoo.cosec.spring.boot.starter.authorization

import io.mockk.mockk
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationChecker
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

internal class CoSecAuthorizationCheckerAutoConfigurationTest {
    private val platformRoot = SimpleTenantPrincipal(SimplePrincipal(CoSecPrincipal.ROOT_ID), SimpleTenant.PLATFORM)

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
            CoSecRequestParserAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
            CoSecAuthorizationCheckerAutoConfiguration::class.java,
        )

    @Test
    fun checkerDecidesWithSimpleAuthorization() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(AuthorizationChecker::class.java)
            val checker = context.getBean(AuthorizationChecker::class.java)
            checker.check(
                SimpleSecurityContext(SimplePrincipal("user")),
                "/orders"
            ).block().assert().isFalse()
            // Root is disabled by default.
            checker.check(SimpleSecurityContext(platformRoot), "/orders").block().assert().isFalse()
        }
    }

    @Test
    fun checkerSharesTheRootPrincipalMatcher() {
        contextRunner.withPropertyValues("cosec.authorization.root-enabled=true").run { context ->
            val checker = context.getBean(AuthorizationChecker::class.java)
            checker.check(SimpleSecurityContext(platformRoot), "/orders").block().assert().isTrue()
            // The root id outside the platform tenant is not root.
            checker.check(
                SimpleSecurityContext(SimplePrincipal(CoSecPrincipal.ROOT_ID)),
                "/orders"
            ).block().assert().isFalse()
        }
    }

    @Test
    fun disabledWithAuthorization() {
        contextRunner.withPropertyValues("cosec.authorization.enabled=false").run { context ->
            assertThat(context).doesNotHaveBean(AuthorizationChecker::class.java)
        }
    }
}
