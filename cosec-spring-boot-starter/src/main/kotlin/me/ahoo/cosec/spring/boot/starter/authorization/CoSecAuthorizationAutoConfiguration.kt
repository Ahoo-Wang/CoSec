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

import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.apikey.ApiKeySecurityContextParser
import me.ahoo.cosec.apikey.ApiKeyStore
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.limiter.RateLimiter
import me.ahoo.cosec.limiter.RateLimitingAuthorization
import me.ahoo.cosec.policy.LocalPolicyInitializer
import me.ahoo.cosec.policy.LocalPolicyLoader
import me.ahoo.cosec.servlet.AuthorizationFilter
import me.ahoo.cosec.shadow.LoggingShadowEventSink
import me.ahoo.cosec.shadow.ShadowAuthorization
import me.ahoo.cosec.shadow.ShadowEventSink
import me.ahoo.cosec.shadow.ShadowPolicyProvider
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.apikey.ApiKeyProperties
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.REACTIVE_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.SERVLET_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.spring.boot.starter.policy.LocalPolicyInitializerLifecycle
import me.ahoo.cosec.spring.boot.starter.shadow.ShadowProperties
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosec.webflux.ReactiveAuthorizationFilter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.server.ServerWebExchange

/**
 * CoSec Authorization AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnAuthorizationEnabled
@EnableConfigurationProperties(
    AuthorizationProperties::class,
)
class CoSecAuthorizationAutoConfiguration {
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    internal annotation class AutoConfigured

    @Bean
    @ConditionalOnMissingBean
    fun securityContextParser(
        tokenVerifier: TokenVerifier,
        authorizationProperties: AuthorizationProperties,
        apiKeyStore: ObjectProvider<ApiKeyStore>,
        apiKeyProperties: ObjectProvider<ApiKeyProperties>
    ): SecurityContextParser {
        val tokenParser = DefaultSecurityContextParser(tokenVerifier, authorizationProperties.allowQueryToken)
        val store = apiKeyStore.ifAvailable ?: return tokenParser
        val header = apiKeyProperties.ifAvailable?.header ?: ApiKeySecurityContextParser.DEFAULT_HEADER
        return ApiKeySecurityContextParser(tokenParser, store, header)
    }

    @Bean
    @ConditionalOnProperty(
        value = [AuthorizationProperties.LOCAL_POLICY_ENABLED],
        matchIfMissing = false,
        havingValue = "true",
    )
    fun localPolicyLoader(authorizationProperties: AuthorizationProperties): LocalPolicyLoader {
        return LocalPolicyLoader(authorizationProperties.localPolicy.locations)
    }

    @Bean
    @ConditionalOnProperty(
        value = [AuthorizationProperties.LOCAL_POLICY_INIT_REPOSITORY],
        matchIfMissing = false,
        havingValue = "true",
    )
    fun localPolicyInitializer(
        localPolicyLoader: LocalPolicyLoader,
        policyRepository: PolicyRepository,
        authorizationProperties: AuthorizationProperties
    ): LocalPolicyInitializer {
        return LocalPolicyInitializer(
            localPolicyLoader = localPolicyLoader,
            policyRepository = policyRepository,
            forceRefresh = authorizationProperties.localPolicy.forceRefresh
        )
    }

    @Bean
    @ConditionalOnProperty(
        value = [AuthorizationProperties.LOCAL_POLICY_INIT_REPOSITORY],
        matchIfMissing = false,
        havingValue = "true",
    )
    fun localPolicyInitializerLifecycle(
        @Qualifier("localPolicyInitializer") localPolicyInitializer: LocalPolicyInitializer
    ): LocalPolicyInitializerLifecycle {
        return LocalPolicyInitializerLifecycle(localPolicyInitializer)
    }

    @Bean
    @ConditionalOnMissingBean
    fun blacklistChecker(): BlacklistChecker {
        return BlacklistChecker.NoOp
    }

    @Bean
    @ConditionalOnMissingBean
    fun rootPrincipalMatcher(authorizationProperties: AuthorizationProperties): RootPrincipalMatcher {
        return if (authorizationProperties.rootEnabled) RootPrincipalMatcher.DEFAULT else RootPrincipalMatcher.NONE
    }

    @Bean
    @AutoConfigured
    @ConditionalOnMissingBean
    fun cosecAuthorization(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker,
        rootPrincipalMatcher: RootPrincipalMatcher,
        rateLimitRuleProvider: ObjectProvider<RateLimitRuleProvider>,
        rateLimiter: ObjectProvider<RateLimiter>,
        shadow: ObjectProvider<ShadowProperties>,
        shadowPolicyProvider: ObjectProvider<ShadowPolicyProvider>,
        shadowEventSink: ObjectProvider<ShadowEventSink>,
        customizers: ObjectProvider<CoSecAuthorizationCustomizer>
    ): Authorization {
        val simpleAuthorization = SimpleAuthorization(
            policyRepository = policyRepository,
            appRolePermissionRepository = appRolePermissionRepository,
            blacklistChecker = blacklistChecker,
            rootPrincipalMatcher = rootPrincipalMatcher,
        )
        // Shadowing compares pure decisions, so it wraps SimpleAuthorization directly, inside rate limiting.
        val shadowProperties = shadow.ifAvailable
        val candidates = shadowPolicyProvider.ifAvailable
        val authorization = if (shadowProperties != null && candidates != null) {
            ShadowAuthorization(
                delegate = simpleAuthorization,
                policyRepository = policyRepository,
                appRolePermissionRepository = appRolePermissionRepository,
                policyProvider = candidates,
                sink = shadowEventSink.getIfAvailable { LoggingShadowEventSink() },
                blacklistChecker = blacklistChecker,
                rootPrincipalMatcher = rootPrincipalMatcher,
                sampleRate = shadowProperties.sampleRate,
                reportRuleChanges = shadowProperties.reportRuleChanges,
            )
        } else {
            simpleAuthorization
        }
        // Rate limiting sits inside auditing and tracing, which wrap this bean, so 429s are audited and traced.
        val ruleProvider = rateLimitRuleProvider.ifAvailable
        val limiter = rateLimiter.ifAvailable
        val limited: Authorization = if (ruleProvider != null && limiter != null) {
            RateLimitingAuthorization(authorization, ruleProvider, limiter)
        } else {
            authorization
        }
        return customizers.orderedStream().toList().fold(
            limited
        ) { current, customizer -> customizer.customize(current) }
    }

    @Configuration
    @ConditionalOnClass(name = ["me.ahoo.cosec.servlet.AuthorizationFilter"])
    class WebMvc {

        @Bean
        @ConditionalOnMissingBean
        fun authorizationFilter(
            securityContextParser: SecurityContextParser,
            authorization: Authorization,
            @Qualifier(SERVLET_REQUEST_PARSER_BEAN_NAME) requestParser: RequestParser<HttpServletRequest>
        ): AuthorizationFilter {
            return AuthorizationFilter(securityContextParser, authorization, requestParser)
        }
    }

    @Configuration
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.ReactiveAuthorizationFilter"])
    class WebFlux {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnMissingClass("org.springframework.cloud.gateway.filter.GlobalFilter")
        fun reactiveAuthorizationFilter(
            securityContextParser: SecurityContextParser,
            @Qualifier(REACTIVE_REQUEST_PARSER_BEAN_NAME) requestParser: RequestParser<ServerWebExchange>,
            authorization: Authorization,
            environment: Environment
        ): ReactiveAuthorizationFilter {
            return ReactiveAuthorizationFilter(
                securityContextParser,
                requestParser,
                authorization,
                environment.securityContextParseScheduler()
            )
        }
    }

    companion object {
        const val BEAN_NAME = "cosecAuthorization"
    }
}
