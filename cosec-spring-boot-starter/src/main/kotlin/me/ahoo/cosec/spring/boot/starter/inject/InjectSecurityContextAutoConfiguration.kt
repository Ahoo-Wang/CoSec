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
package me.ahoo.cosec.spring.boot.starter.inject

import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.servlet.InjectSecurityContextFilter
import me.ahoo.cosec.servlet.InjectSecurityContextParser
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.webflux.ReactiveInjectSecurityContextParser
import me.ahoo.cosec.webflux.ReactiveInjectSecurityContextWebFilter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ServerWebExchange
import javax.servlet.http.HttpServletRequest

/**
 * InjectSecurityContextAutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnInjectSecurityEnabled
@EnableConfigurationProperties(
    InjectSecurityContextProperties::class
)
class InjectSecurityContextAutoConfiguration {
    companion object {
        const val INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME = "injectSecurityContextParser"
        const val REACTIVE_INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME = "reactiveInjectSecurityContextParser"
    }

    @Configuration
    @ConditionalOnClass(InjectSecurityContextFilter::class)
    class WebMvc {

        @Bean(INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME])
        fun injectSecurityContextParser(): SecurityContextParser<HttpServletRequest> {
            return InjectSecurityContextParser
        }

        @Bean
        @ConditionalOnMissingBean
        fun injectSecurityContextFilter(
            @Qualifier(INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME) securityContextParser: SecurityContextParser<HttpServletRequest>
        ): InjectSecurityContextFilter {
            return InjectSecurityContextFilter(securityContextParser)
        }
    }

    @Configuration
    @ConditionalOnClass(ReactiveInjectSecurityContextWebFilter::class)
    class WebFlux {

        @Bean(REACTIVE_INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME)
        @ConditionalOnMissingBean(name = [REACTIVE_INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME])
        fun reactiveInjectSecurityContextParser(): SecurityContextParser<ServerWebExchange> {
            return ReactiveInjectSecurityContextParser
        }

        @Bean
        @ConditionalOnMissingBean
        fun reactiveInjectSecurityContextWebFilter(
            @Qualifier(REACTIVE_INJECT_SECURITY_CONTEXT_PARSER_BEAN_NAME) securityContextParser: SecurityContextParser<ServerWebExchange>
        ): ReactiveInjectSecurityContextWebFilter {
            return ReactiveInjectSecurityContextWebFilter(securityContextParser)
        }
    }
}
