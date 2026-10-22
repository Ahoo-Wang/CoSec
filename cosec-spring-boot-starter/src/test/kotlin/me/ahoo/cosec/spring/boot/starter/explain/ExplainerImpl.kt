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
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The two ways of explaining a decision until the narration is removed (RFC 0002 §5.2, PR-6). A copy of cosec-core's
 * test fixture of the same name, removed with it.
 */
enum class ExplainerImpl {
    /** The starter's own explainer, which narrates. */
    NARRATION,

    /**
     * An explainer of the engine's trace, registered by [TraceExplainerConfiguration] ahead of the starter's, which
     * then backs off.
     */
    TRACE,
}

/**
 * Builds the trace-based explainer from the same beans as `CoSecExplainAutoConfiguration`. Production cannot choose
 * it: only cosec-core reaches the explainer's private constructor, so this test does through reflection.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(value = [ExplainProperties.ENABLED_KEY], havingValue = "true")
class TraceExplainerConfiguration {
    @Bean
    fun traceAuthorizationExplainer(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: ObjectProvider<BlacklistChecker>,
        rootPrincipalMatcher: ObjectProvider<RootPrincipalMatcher>,
        rateLimitRuleProvider: ObjectProvider<RateLimitRuleProvider>,
        securityContextParser: ObjectProvider<SecurityContextParser>
    ): AuthorizationExplainer = AuthorizationExplainer::class.java.getDeclaredConstructor(
        PolicyRepository::class.java,
        AppRolePermissionRepository::class.java,
        BlacklistChecker::class.java,
        RootPrincipalMatcher::class.java,
        RateLimitRuleProvider::class.java,
        SecurityContextParser::class.java,
        Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }.newInstance(
        policyRepository,
        appRolePermissionRepository,
        blacklistChecker.getIfAvailable { BlacklistChecker.NoOp },
        rootPrincipalMatcher.getIfAvailable { RootPrincipalMatcher.NONE },
        rateLimitRuleProvider.ifAvailable,
        securityContextParser.ifAvailable,
        true,
    )
}
