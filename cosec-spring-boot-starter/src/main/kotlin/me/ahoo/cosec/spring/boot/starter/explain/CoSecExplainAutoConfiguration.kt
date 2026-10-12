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

import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.limiter.CoSecRateLimitAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * CoSec Explain AutoConfiguration, active only with `cosec.explain.enabled=true`.
 *
 * Builds the [AuthorizationExplainer] from the same components as the authorization, and the
 * [CoSecExplainEndpoint] when Spring Boot Actuator is present.
 */
@AutoConfiguration(after = [CoSecAuthorizationAutoConfiguration::class, CoSecRateLimitAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnProperty(value = [ExplainProperties.ENABLED_KEY], havingValue = "true")
@EnableConfigurationProperties(ExplainProperties::class)
class CoSecExplainAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun authorizationExplainer(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: ObjectProvider<BlacklistChecker>,
        rootPrincipalMatcher: ObjectProvider<RootPrincipalMatcher>,
        rateLimitRuleProvider: ObjectProvider<RateLimitRuleProvider>
    ): AuthorizationExplainer {
        return AuthorizationExplainer(
            policyRepository = policyRepository,
            appRolePermissionRepository = appRolePermissionRepository,
            blacklistChecker = blacklistChecker.getIfAvailable { BlacklistChecker.NoOp },
            rootPrincipalMatcher = rootPrincipalMatcher.getIfAvailable { RootPrincipalMatcher.DEFAULT },
            rateLimitRuleProvider = rateLimitRuleProvider.ifAvailable,
        )
    }

    @Configuration
    @ConditionalOnClass(name = ["org.springframework.boot.actuate.endpoint.annotation.Endpoint"])
    class ActuatorEndpoint {
        @Bean
        @ConditionalOnMissingBean
        fun coSecExplainEndpoint(authorizationExplainer: AuthorizationExplainer): CoSecExplainEndpoint {
            return CoSecExplainEndpoint(authorizationExplainer)
        }
    }
}
