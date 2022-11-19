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

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.authorization.Authorization
import me.ahoo.cosec.authorization.PermissionRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.context.request.RequestTenantIdParser
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.servlet.AuthorizationFilter
import me.ahoo.cosec.servlet.ServletRequestParser
import me.ahoo.cosec.servlet.ServletRequestSecurityContextParser
import me.ahoo.cosec.servlet.ServletRequestTenantIdParser
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.cosec.webflux.ReactiveAuthorizationFilter
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.cosec.webflux.ReactiveRequestTenantIdParser
import me.ahoo.cosec.webflux.ReactiveSecurityContextParser
import me.ahoo.cosid.IdGenerator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ServerWebExchange
import javax.servlet.http.HttpServletRequest

/**
 * CoSec Authorization AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnAuthorizationEnabled
@EnableConfigurationProperties(
    AuthorizationProperties::class
)
class CoSecAuthorizationAutoConfiguration(private val authorizationProperties: AuthorizationProperties) {

    @Bean
    @ConditionalOnMissingBean
    fun cosecTokenAlgorithm(): Algorithm {
        val jwtProperties = authorizationProperties.jwt
        return when (jwtProperties.algorithm) {
            JwtProperties.Algorithm.HMAC256 -> Algorithm.HMAC256(
                jwtProperties.secret
            )

            JwtProperties.Algorithm.HMAC384 -> Algorithm.HMAC384(
                jwtProperties.secret
            )

            JwtProperties.Algorithm.HMAC512 -> Algorithm.HMAC512(
                jwtProperties.secret
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecTokenConverter(idGenerator: IdGenerator, algorithm: Algorithm): TokenConverter {
        val jwtProperties = authorizationProperties.jwt
        return JwtTokenConverter(
            idGenerator,
            algorithm,
            jwtProperties.tokenValidity.access,
            jwtProperties.tokenValidity.refresh
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecAuthorization(
        permissionRepository: PermissionRepository
    ): Authorization {
        return SimpleAuthorization(permissionRepository)
    }

    companion object {
        const val SERVLET_REQUEST_TENANT_ID_PARSER_BEAN_NAME = "servletRequestTenantIdParser"
        const val SERVLET_REQUEST_PARSER_BEAN_NAME = "servletRequestParser"
        const val SERVLET_SECURITY_CONTEXT_PARSER_BEAN_NAME = "servletSecurityContextParser"
        const val REACTIVE_REQUEST_TENANT_ID_PARSER_BEAN_NAME = "reactiveRequestTenantIdParser"
        const val REACTIVE_REQUEST_PARSER_BEAN_NAME = "reactiveRequestParser"
        const val REACTIVE_SECURITY_CONTEXT_PARSER_BEAN_NAME = "reactiveSecurityContextParser"
    }

    @Configuration
    @ConditionalOnClass(name = ["me.ahoo.cosec.servlet.AuthorizationFilter"])
    class WebMvc {

        @Bean(SERVLET_REQUEST_TENANT_ID_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [SERVLET_REQUEST_TENANT_ID_PARSER_BEAN_NAME])
        fun servletRequestTenantIdParser(): RequestTenantIdParser<HttpServletRequest> {
            return ServletRequestTenantIdParser.INSTANCE
        }

        @Bean(SERVLET_REQUEST_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [SERVLET_REQUEST_PARSER_BEAN_NAME])
        fun servletRequestParser(requestTenantIdParser: RequestTenantIdParser<HttpServletRequest>): RequestParser<HttpServletRequest> {
            return ServletRequestParser(requestTenantIdParser)
        }

        @Bean(SERVLET_SECURITY_CONTEXT_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [SERVLET_SECURITY_CONTEXT_PARSER_BEAN_NAME])
        fun servletSecurityContextParser(
            tokenConverter: TokenConverter
        ): SecurityContextParser<HttpServletRequest> {
            return ServletRequestSecurityContextParser(tokenConverter)
        }

        @Bean
        @ConditionalOnMissingBean
        fun authorizationFilter(
            @Qualifier(SERVLET_SECURITY_CONTEXT_PARSER_BEAN_NAME) securityContextParser: SecurityContextParser<HttpServletRequest>,
            authorization: Authorization,
            @Qualifier(SERVLET_REQUEST_PARSER_BEAN_NAME) requestParser: RequestParser<HttpServletRequest>
        ): AuthorizationFilter {
            return AuthorizationFilter(securityContextParser, authorization, requestParser)
        }
    }

    @Configuration
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.ReactiveAuthorizationFilter"])
    class WebFlux {
        @Bean(REACTIVE_REQUEST_TENANT_ID_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [REACTIVE_REQUEST_TENANT_ID_PARSER_BEAN_NAME])
        fun reactiveRequestTenantIdParser(): RequestTenantIdParser<ServerWebExchange> {
            return ReactiveRequestTenantIdParser.INSTANCE
        }

        @Bean(REACTIVE_REQUEST_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [REACTIVE_REQUEST_PARSER_BEAN_NAME])
        fun reactiveRequestParser(reactiveRequestTenantIdParser: RequestTenantIdParser<ServerWebExchange>): RequestParser<ServerWebExchange> {
            return ReactiveRequestParser(reactiveRequestTenantIdParser)
        }

        @Bean(REACTIVE_SECURITY_CONTEXT_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [REACTIVE_SECURITY_CONTEXT_PARSER_BEAN_NAME])
        fun reactiveSecurityContextParser(
            tokenConverter: TokenConverter
        ): SecurityContextParser<ServerWebExchange> {
            return ReactiveSecurityContextParser(tokenConverter)
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnMissingClass("org.springframework.cloud.gateway.filter.GlobalFilter")
        fun reactiveAuthorizationFilter(
            @Qualifier(REACTIVE_SECURITY_CONTEXT_PARSER_BEAN_NAME) securityContextParser: SecurityContextParser<ServerWebExchange>,
            @Qualifier(REACTIVE_REQUEST_PARSER_BEAN_NAME) requestParser: RequestParser<ServerWebExchange>,
            authorization: Authorization
        ): ReactiveAuthorizationFilter {
            return ReactiveAuthorizationFilter(securityContextParser, requestParser, authorization)
        }
    }
}
