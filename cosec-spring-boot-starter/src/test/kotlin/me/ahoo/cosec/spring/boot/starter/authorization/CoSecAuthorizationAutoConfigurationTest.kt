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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.cache.limiter.RedisRateLimiterConditionMatcherFactory
import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.policy.LocalPolicyInitializer
import me.ahoo.cosec.policy.LocalPolicyLoader
import me.ahoo.cosec.servlet.AuthorizationFilter
import me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CoSecPermissionCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CoSecPolicyCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.limiter.CoSecRedisRateLimiterAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.ip2region.Ip2RegionAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.starter.policy.MatcherFactoryRegister
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import reactor.core.publisher.Mono

internal class CoSecAuthorizationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "${AuthorizationProperties.LOCAL_POLICY_ENABLED}=true",
                "${AuthorizationProperties.LOCAL_POLICY_INIT_REPOSITORY}=true",
            )
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withUserConfiguration(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPolicyCacheAutoConfiguration::class.java,
                CoSecPermissionCacheAutoConfiguration::class.java,
                CoSecRequestParserAutoConfiguration::class.java,
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                Ip2RegionAutoConfiguration::class.java,
                CoSecAuthorizationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(AuthorizationProperties::class.java)
                    .hasSingleBean(CoSecAuthorizationAutoConfiguration::class.java)
                    .hasSingleBean(LocalPolicyLoader::class.java)
                    .hasSingleBean(LocalPolicyInitializer::class.java)
                    .hasSingleBean(DefaultSecurityContextParser::class.java)
                    .hasSingleBean(BlacklistChecker::class.java)
                    .hasSingleBean(Authorization::class.java)
                    .hasSingleBean(AuthorizationFilter::class.java)
            }
    }

    @Test
    fun localPolicyInitializationRunsAfterMatcherRegistration() {
        val storedPolicies = mutableListOf<Policy>()
        val policyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.just(listOf())
            every { setPolicy(capture(storedPolicies)) } returns Mono.empty()
        }

        contextRunner
            .withPropertyValues(
                "${AuthorizationProperties.LOCAL_POLICY_ENABLED}=true",
                "${AuthorizationProperties.LOCAL_POLICY_INIT_REPOSITORY}=true",
                "${AuthorizationProperties.LOCAL_POLICY_PREFIX}.locations=" +
                    "classpath:cosec-policy/redis-rate-limiter-policy.json",
            )
            .withBean(PolicyRepository::class.java, { policyRepository })
            .withBean(AppRolePermissionRepository::class.java, { mockk() })
            .withBean(TokenVerifier::class.java, { mockk() })
            .withBean(StringRedisTemplate::class.java, { mockk(relaxed = true) })
            .withUserConfiguration(
                CoSecAutoConfiguration::class.java,
                CoSecRedisRateLimiterAutoConfiguration::class.java,
                CoSecRequestParserAutoConfiguration::class.java,
                CoSecAuthorizationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(MatcherFactoryRegister::class.java)
                context.getBean(MatcherFactoryRegister::class.java).isRunning.assert().isTrue()
                storedPolicies.single().statements.single().condition.type.assert()
                    .isEqualTo(RedisRateLimiterConditionMatcherFactory.TYPE)
            }
    }
}
