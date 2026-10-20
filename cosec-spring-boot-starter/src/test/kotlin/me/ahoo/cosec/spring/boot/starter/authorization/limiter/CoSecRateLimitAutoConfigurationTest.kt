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

package me.ahoo.cosec.spring.boot.starter.authorization.limiter

import io.mockk.mockk
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.limiter.LocalRateLimiter
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.limiter.RateLimiter
import me.ahoo.cosec.limiter.RateLimitingAuthorization
import me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration
import me.ahoo.cosec.token.TokenVerifier
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecRateLimitAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withBean(PolicyRepository::class.java, { mockk() })
        .withBean(AppRolePermissionRepository::class.java, { mockk() })
        .withBean(TokenVerifier::class.java, { mockk() })
        .withUserConfiguration(
            CoSecAutoConfiguration::class.java,
            CoSecRateLimitAutoConfiguration::class.java,
            CoSecRequestParserAutoConfiguration::class.java,
            CoSecAuthorizationAutoConfiguration::class.java,
        )

    @Test
    fun wrapsAuthorizationWithLocalRateLimiter() {
        contextRunner
            .withPropertyValues("cosec.limiter.locations=classpath:cosec-rate-limit/*-rate-limit.json")
            .run { context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(RateLimitRuleProvider::class.java)
                    .hasSingleBean(RateLimitRuleLoaderLifecycle::class.java)
                assertThat(context.getBean(RateLimiter::class.java)).isInstanceOf(LocalRateLimiter::class.java)
                assertThat(context.getBean(RateLimitRuleProvider::class.java).getRules().map { it.id })
                    .containsExactly("api-by-ip")
                val authorization = context.getBean(Authorization::class.java)
                assertThat(authorization).isInstanceOf(RateLimitingAuthorization::class.java)
                assertThat((authorization as RateLimitingAuthorization).delegate)
                    .isInstanceOf(SimpleAuthorization::class.java)
                assertThat(context.getBean(RateLimitRuleLoaderLifecycle::class.java).isRunning).isTrue()
            }
    }

    @Test
    fun invalidRuleFailsStartup() {
        contextRunner
            .withPropertyValues("cosec.limiter.locations=classpath:cosec-rate-limit/invalid/*.json")
            .run { context ->
                assertThat(context).hasFailed()
            }
    }

    @Test
    fun missingRuleLocationFailsStartup() {
        contextRunner
            .withPropertyValues("cosec.limiter.locations=classpath:cosec-rate-limit-typo/*-rate-limit.json")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining(
                        "Rate limit rule location [classpath:cosec-rate-limit-typo/*-rate-limit.json]"
                    )
            }
    }

    @Test
    fun disabledKeepsPlainAuthorization() {
        contextRunner
            .withPropertyValues("cosec.limiter.enabled=false")
            .run { context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(RateLimitRuleProvider::class.java)
                    .doesNotHaveBean(RateLimiter::class.java)
                assertThat(context.getBean(Authorization::class.java)).isInstanceOf(SimpleAuthorization::class.java)
            }
    }
}
