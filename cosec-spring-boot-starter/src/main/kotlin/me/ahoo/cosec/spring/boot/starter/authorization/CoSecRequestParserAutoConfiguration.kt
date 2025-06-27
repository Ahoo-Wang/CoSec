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
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.servlet.ServletRequestParser
import me.ahoo.cosec.servlet.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.jvm.UuidGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ServerWebExchange

/**
 * CoSec Request Parser AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
class CoSecRequestParserAutoConfiguration {

    companion object {
        const val SERVLET_REMOTE_IP_RESOLVER_BEAN_NAME = "servletRemoteIpResolver"
        const val SERVLET_REQUEST_PARSER_BEAN_NAME = "servletRequestParser"
        const val REACTIVE_REMOTE_IP_RESOLVER_BEAN_NAME = "reactiveRemoteIpResolver"
        const val REACTIVE_REQUEST_PARSER_BEAN_NAME = "reactiveRequestParser"
    }

    @Configuration
    @ConditionalOnClass(name = ["me.ahoo.cosec.servlet.AuthorizationFilter"])
    class WebMvc {

        @Bean(SERVLET_REMOTE_IP_RESOLVER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [SERVLET_REMOTE_IP_RESOLVER_BEAN_NAME])
        fun servletRemoteIpResolver(): RemoteIpResolver<HttpServletRequest> {
            return ServletXForwardedRemoteIpResolver.TRUST_ALL
        }

        @Bean(SERVLET_REQUEST_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [SERVLET_REQUEST_PARSER_BEAN_NAME])
        fun servletRequestParser(
            servletRemoteIpResolver: RemoteIpResolver<HttpServletRequest>,
            requestAttributesAppenderObjectProvider: ObjectProvider<RequestAttributesAppender>
        ): RequestParser<HttpServletRequest> {
            return ServletRequestParser(servletRemoteIpResolver, requestAttributesAppenderObjectProvider.toList())
        }
    }

    @Configuration
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.ReactiveAuthorizationFilter"])
    class WebFlux {

        @Bean(REACTIVE_REMOTE_IP_RESOLVER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [REACTIVE_REMOTE_IP_RESOLVER_BEAN_NAME])
        fun reactiveRemoteIpResolver(): RemoteIpResolver<ServerWebExchange> {
            return ReactiveXForwardedRemoteIpResolver.TRUST_ALL
        }

        @Bean(REACTIVE_REQUEST_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [REACTIVE_REQUEST_PARSER_BEAN_NAME])
        fun reactiveRequestParser(
            reactiveRemoteIpResolver: RemoteIpResolver<ServerWebExchange>,
            idGeneratorObjectProvider: ObjectProvider<IdGenerator>,
            requestAttributesAppenderObjectProvider: ObjectProvider<RequestAttributesAppender>
        ): RequestParser<ServerWebExchange> {
            val idGenerator = idGeneratorObjectProvider.getIfAvailable { UuidGenerator.INSTANCE }
            return ReactiveRequestParser(
                remoteIpResolver = reactiveRemoteIpResolver,
                requestAttributesAppends = requestAttributesAppenderObjectProvider.toList(),
                idGenerator = idGenerator
            )
        }
    }
}
