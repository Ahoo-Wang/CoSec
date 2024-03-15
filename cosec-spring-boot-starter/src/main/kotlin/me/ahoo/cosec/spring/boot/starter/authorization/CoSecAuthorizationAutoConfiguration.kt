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
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.policy.LocalPolicyInitializer
import me.ahoo.cosec.policy.LocalPolicyLoader
import me.ahoo.cosec.servlet.AuthorizationFilter
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.REACTIVE_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.SERVLET_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosec.webflux.ReactiveAuthorizationFilter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    @Bean
    @ConditionalOnMissingBean
    fun securityContextParser(tokenVerifier: TokenVerifier): SecurityContextParser {
        return DefaultSecurityContextParser(tokenVerifier)
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

    @Bean(initMethod = "init")
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
    @ConditionalOnMissingBean
    fun cosecAuthorization(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository
    ): Authorization {
        return SimpleAuthorization(policyRepository, appRolePermissionRepository)
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
            authorization: Authorization
        ): ReactiveAuthorizationFilter {
            return ReactiveAuthorizationFilter(securityContextParser, requestParser, authorization)
        }
    }
}
