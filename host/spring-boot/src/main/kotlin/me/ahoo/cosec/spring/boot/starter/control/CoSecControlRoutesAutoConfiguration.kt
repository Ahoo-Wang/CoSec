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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.starter.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlHttpResponse
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.ProblemType
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.servlet.ServletRequests.getSecurityContext
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.spring.boot.starter.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.io.InputStream
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
            val base = apiRootOf(properties)
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
            val base = apiRootOf(properties)
            val maxBodySize = properties.http.maxBodySize.toBytes()
            return ServletRouterFunctions.route(ServletPredicates.path("$base/**")) { request ->
                val servlet = request.servletRequest()
                val security = RequestSecurity.of(servlet.getSecurityContext())
                // Nothing is read for a request no permitted principal stands behind (ControlHttp answers 401), and
                // never more than max-body-size: the body is read before ControlHttp authorizes the action.
                val body = if (security == null) {
                    EMPTY
                } else {
                    readBody(servlet.inputStream, maxBodySize)
                        ?: return@route tooLarge(maxBodySize)
                }
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

    /**
     * On a Spring Cloud Gateway host the gateway's global filter runs on its routes only, so the control routes would
     * never pass the pipeline and every call would be 401. A [PipelineWebFilter] limited to the control base path
     * authorizes them as on a WebFlux host; the gateway's own routes keep the gateway filter, and actuator endpoints
     * stay outside CoSec, as before.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.PipelineWebFilter", GATEWAY_GLOBAL_FILTER])
    class GatewayHost {
        @Bean
        fun cosecControlPipelineWebFilter(
            pipeline: Pipeline,
            contributors: ObjectProvider<BridgeContextContributor>,
            authorization: RuntimeAuthorizationProperties,
            properties: ControlProperties,
        ): ControlRoutesWebFilter = ControlRoutesWebFilter(
            PipelineWebFilter(
                pipeline,
                contributors.orderedStream().toList(),
                ReactiveXForwardedRemoteIpResolver(authorization.remoteIp.maxTrustedIndex),
                CoSecPipelineWebAutoConfiguration.hostOf(authorization),
            ),
            basePathOf(properties),
        )
    }

    internal companion object {
        private val EMPTY = ByteArray(0)

        /** At most [max] bytes of [input], or null when it holds more. */
        fun readBody(input: InputStream, max: Long): ByteArray? = input.use {
            val limit = max.coerceAtMost(Int.MAX_VALUE - 1L).toInt()
            val bytes = it.readNBytes(limit + 1)
            bytes.takeIf { read -> read.size <= limit }
        }

        fun tooLarge(max: Long): ServletServerResponse = ServletServerResponse.status(
            ProblemType.INVALID_REQUEST.status
        )
            .contentType(MediaType.parseMediaType(ControlHttp.PROBLEM_JSON))
            .body(
                mapOf(
                    "type" to ProblemType.INVALID_REQUEST.uri,
                    "title" to ProblemType.INVALID_REQUEST.title,
                    "status" to ProblemType.INVALID_REQUEST.status,
                    "code" to ProblemType.INVALID_REQUEST.code,
                    "detail" to "The request body is larger than ${ControlProperties.PREFIX}.http.max-body-size ($max bytes).",
                ),
            )

        fun basePathOf(properties: ControlProperties): String = "/" + properties.http.basePath.trim('/')

        /**
         * Where this version of the API is served: `{base-path}/v1`. The version is fixed here, not part of
         * `base-path`, so moving the API never drops it.
         */
        fun apiRootOf(properties: ControlProperties): String = basePathOf(properties) + "/" + API_VERSION

        const val API_VERSION = "v1"

        fun relative(path: String, base: String): String = path.removePrefix(base).ifEmpty { "/" }
    }
}

/** [delegate] for the requests under [basePath] only; every other request goes on untouched. */
class ControlRoutesWebFilter(
    private val delegate: PipelineWebFilter,
    private val basePath: String
) : WebFilter, Ordered {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication().value()
        return if (path == basePath || path.startsWith("$basePath/")) {
            delegate.filter(
                exchange,
                chain
            )
        } else {
            chain.filter(exchange)
        }
    }

    override fun getOrder(): Int = delegate.order
}
