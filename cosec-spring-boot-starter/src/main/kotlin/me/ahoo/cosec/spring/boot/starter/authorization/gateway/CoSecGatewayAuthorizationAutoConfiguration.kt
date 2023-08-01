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
package me.ahoo.cosec.spring.boot.starter.authorization.gateway

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.gateway.AuthorizationGatewayFilter
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.REACTIVE_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.spring.boot.starter.authorization.ConditionalOnAuthorizationEnabled
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.server.ServerWebExchange

/**
 * CoSec Authorization AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnAuthorizationEnabled
@ConditionalOnGatewayEnabled
@ConditionalOnClass(AuthorizationGatewayFilter::class)
@EnableConfigurationProperties(
    GatewayProperties::class,
)
class CoSecGatewayAuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun authorizationGatewayFilter(
        securityContextParser: SecurityContextParser,
        @Qualifier(REACTIVE_REQUEST_PARSER_BEAN_NAME) requestParser: RequestParser<ServerWebExchange>,
        authorization: Authorization
    ): AuthorizationGatewayFilter {
        return AuthorizationGatewayFilter(securityContextParser, requestParser, authorization)
    }
}
