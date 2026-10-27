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

package me.ahoo.cosec.spring.boot.starter.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlHttpResponse
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.servlet.ServletRequests.getSecurityContext
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates as ReactivePredicates
import org.springframework.web.reactive.function.server.RouterFunction as ReactiveRouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions as ReactiveRouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse as ReactiveServerResponse
import org.springframework.web.servlet.function.RequestPredicates as ServletPredicates
import org.springframework.web.servlet.function.RouterFunction as ServletRouterFunction
import org.springframework.web.servlet.function.RouterFunctions as ServletRouterFunctions
import org.springframework.web.servlet.function.ServerResponse as ServletServerResponse

/**
 * The control API as functional routes under `cosec.control.http.base-path` (RFC 0007 §10.2). The pipeline has
 * already authenticated and permitted the request; [ControlHttp] authorizes each control-plane action again as the
 * principal of that request, and answers 401 when no permitted request stands behind it.
 */
@AutoConfiguration(after = [CoSecControlAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnBean(ControlHttp::class)
class CoSecControlRoutesAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.ServerWebExchanges"])
    class WebFlux {
        @Bean
        fun cosecControlRoutes(
            http: ControlHttp,
            properties: ControlProperties
        ): ReactiveRouterFunction<ReactiveServerResponse> {
            val base = basePathOf(properties)
            return ReactiveRouterFunctions.route(ReactivePredicates.path("$base/**")) { request ->
                val security = RequestSecurity.of(request.exchange().getSecurityContext())
                request.bodyToMono(ByteArray::class.java).defaultIfEmpty(EMPTY).flatMap { body ->
                    mono(Dispatchers.Default) {
                        http.handle(
                            ControlHttpRequest(
                                request.method().name(),
                                relative(request.path(), base),
                                request.queryParams().toSingleValueMap(),
                                body
                            ),
                            security,
                        )
                    }
                }.flatMap { response ->
                    ReactiveServerResponse.status(response.status)
                        .contentType(MediaType.parseMediaType(response.contentType))
                        .bodyValue(response.body)
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["me.ahoo.cosec.servlet.ServletRequests"])
    class WebMvc {
        @Bean
        fun cosecControlRoutes(
            http: ControlHttp,
            properties: ControlProperties
        ): ServletRouterFunction<ServletServerResponse> {
            val base = basePathOf(properties)
            return ServletRouterFunctions.route(ServletPredicates.path("$base/**")) { request ->
                val servlet = request.servletRequest()
                val security = RequestSecurity.of(servlet.getSecurityContext())
                val body = servlet.inputStream.use { it.readAllBytes() }
                val query = request.params().toSingleValueMap()
                // A servlet thread is the caller's own: waiting on it blocks no event loop.
                val response: ControlHttpResponse = runBlocking(Dispatchers.Default) {
                    http.handle(
                        ControlHttpRequest(request.method().name(), relative(request.path(), base), query, body),
                        security
                    )
                }
                ServletServerResponse.status(response.status)
                    .contentType(MediaType.parseMediaType(response.contentType))
                    .body(response.body)
            }
        }
    }

    internal companion object {
        private val EMPTY = ByteArray(0)

        fun basePathOf(properties: ControlProperties): String = "/" + properties.http.basePath.trim('/')

        fun relative(path: String, base: String): String = path.removePrefix(base).ifEmpty { "/" }
    }
}
