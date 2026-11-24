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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlHttpResponse
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.api.ControlProfile
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.pipeline.ApplicationPort
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.spring.boot.pipeline.ReactiveClientIp
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ServerWebExchanges.securityContextOrNull
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.BodyExtractors
import reactor.core.publisher.Mono
import java.io.InputStream
import java.net.URLDecoder
import org.springframework.web.reactive.function.server.RequestPredicates as ReactivePredicates
import org.springframework.web.reactive.function.server.RouterFunction as ReactiveRouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions as ReactiveRouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest as ReactiveServerRequest
import org.springframework.web.reactive.function.server.ServerResponse as ReactiveServerResponse
import org.springframework.web.servlet.function.RequestPredicates as ServletPredicates
import org.springframework.web.servlet.function.RouterFunction as ServletRouterFunction
import org.springframework.web.servlet.function.RouterFunctions as ServletRouterFunctions
import org.springframework.web.servlet.function.ServerResponse as ServletServerResponse

/**
 * The control API as functional routes under `cosec.control.http.base-path` (RFC 0007 §10.2). The pipeline has
 * already authenticated and permitted the request; [ControlHttp] authorizes each control-plane action again as the
 * principal of that request, and answers 401 when no permitted request stands behind it, on either stack before any of
 * the body is read (RC1-41).
 */
@AutoConfiguration(after = [CoSecControlAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnBean(ControlHttp::class)
@ConditionalOnProperty(prefix = ControlProperties.PREFIX + ".http", name = ["enabled"], matchIfMissing = true)
internal class CoSecControlRoutesAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.ServerWebExchanges"])
    class WebFlux {
        /**
         * The service port (`CTL-58`), when `cosec.control.http.service-port` is set: a second listener over the same
         * handler, and the filter that serves nothing but the control API on it.
         */
        @Bean
        @ConditionalOnProperty(ControlServicePort.KEY)
        @ConditionalOnClass(name = ["reactor.netty.http.server.HttpServer"])
        fun cosecControlServiceListener(
            context: ApplicationContext,
            properties: ControlProperties,
            environment: Environment,
        ): ControlServiceListener = ControlServiceListener(
            context,
            requireNotNull(properties.http.servicePort),
            ControlServicePort.grace(environment),
        )

        @Bean
        @ConditionalOnProperty(ControlServicePort.KEY)
        @ConditionalOnClass(name = ["reactor.netty.http.server.HttpServer"])
        fun cosecControlServicePortFilter(
            listener: ControlServiceListener,
            properties: ControlProperties,
        ): ServicePortFilter = ServicePortFilter(listener, basePathOf(properties))

        /** The service port's control routes, served by the filter and by no handler of the application (`R3-5`). */
        @Bean
        @ConditionalOnProperty(ControlServicePort.KEY)
        @ConditionalOnClass(name = ["reactor.netty.http.server.HttpServer"])
        fun cosecControlServicePortRoutes(
            listener: ControlServiceListener,
            @Qualifier("cosecControlRoutes") routes: ReactiveRouterFunction<ReactiveServerResponse>,
        ): ServicePortRoutes = ServicePortRoutes({ listener.boundPort }, routes)

        /** Where the application's server bound (`CTL-69`); the login routes' configuration declares it too. */
        @Bean
        @ConditionalOnMissingBean
        fun cosecApplicationPort(): ApplicationPort = ApplicationPort()

        @Bean
        fun cosecControlRoutes(
            operators: ControlHttp,
            properties: ControlProperties,
            applicationPort: ApplicationPort,
            plane: ObjectProvider<ControlPlane>? = null,
            service: ObjectProvider<ControlServiceListener>? = null,
        ): ReactiveRouterFunction<ReactiveServerResponse> =
            routesWithin(operators, properties, applicationPort, plane, service, ControlDerived.MAX_BODY_BYTES)

        /** The routes reading at most [maxBodySize] of a body: the host's is [ControlDerived.MAX_BODY_BYTES], a test's is its own. */
        fun routesWithin(
            operators: ControlHttp,
            properties: ControlProperties,
            applicationPort: ApplicationPort,
            plane: ObjectProvider<ControlPlane>?,
            service: ObjectProvider<ControlServiceListener>?,
            maxBodySize: Long,
        ): ReactiveRouterFunction<ReactiveServerResponse> {
            val base = apiRootOf(properties)
            // The narrow profile answers on the service port only; the operators' port serves the whole API.
            val listener = service?.ifAvailable
            // One work budget for both profiles (CTL-85): the plane's, which is the host's.
            val services = listener?.let { ControlHttp(checkNotNull(plane).getObject(), ControlProfile.SERVICE) }
            // The control API answers on the application's port (the operators' profile) and on the service port (the
            // service profile), and on no other port the host listens on, the management port included (CTL-69): a 404
            // there, before the control plane is asked.
            val served = applicationPort.predicate().or { request ->
                listener != null && request.exchange().request.localAddress?.port == listener.boundPort
            }
            return ReactiveRouterFunctions.route(ReactivePredicates.path("$base/**").and(served)) { request ->
                val onServicePort = listener != null && request.exchange().request.localAddress?.port == listener.boundPort
                val http = if (onServicePort) services!! else operators
                val security = RequestSecurity.of(request.exchange().securityContextOrNull())
                // Nothing is read for a request no permitted principal stands behind, an anonymous permit included:
                // ControlHttp answers it 401 (RC1-41, review L1 of #1240). Never more than the body limit (CTL-41).
                val body = if (security?.principal == null) {
                    Mono.just(EMPTY)
                } else {
                    readBody(request, maxBodySize)
                }
                body.flatMap { bytes ->
                    mono(Dispatchers.Default) {
                        http.handle(
                            ControlHttpRequest(
                                request.method().name(),
                                relative(request.path(), base),
                                request.queryParams().toSingleValueMap(),
                                bytes,
                                ControlPeer.of(request.exchange()),
                            ),
                            security,
                        )
                    }
                }.flatMap { response ->
                    ReactiveServerResponse.status(response.status)
                        .contentType(MediaType.parseMediaType(response.contentType))
                        .headers { headers -> response.headers.forEach(headers::set) }
                        .bodyValue(response.body)
                }.onErrorResume(DataBufferLimitException::class.java) {
                    ReactiveServerResponse.status(ProblemType.INVALID_REQUEST.status)
                        .contentType(MediaType.parseMediaType(ControlHttp.PROBLEM_JSON))
                        .bodyValue(tooLargeProblem(maxBodySize))
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webmvc.ServletRequests"])
    class WebMvc {
        @Bean
        fun cosecControlRoutes(
            http: ControlHttp,
            properties: ControlProperties,
        ): ServletRouterFunction<ServletServerResponse> =
            routesWithin(http, properties, ControlDerived.MAX_BODY_BYTES)

        /** The routes reading at most [maxBodySize] of a body: the host's is [ControlDerived.MAX_BODY_BYTES], a test's is its own. */
        fun routesWithin(
            http: ControlHttp,
            properties: ControlProperties,
            maxBodySize: Long,
        ): ServletRouterFunction<ServletServerResponse> {
            val base = apiRootOf(properties)
            return ServletRouterFunctions.route(ServletPredicates.path("$base/**")) { request ->
                val servlet = request.servletRequest()
                val security = RequestSecurity.of(servlet.securityContextOrNull())
                // Nothing is read for a request no permitted principal stands behind, an anonymous permit included
                // (ControlHttp answers 401), and never more than the body limit: the body is read before ControlHttp
                // authorizes the action. The query comes from the query string alone: the request's parameters would
                // read a form body (review L1 of #1240).
                val body = if (security?.principal == null) {
                    EMPTY
                } else {
                    readBody(servlet.inputStream, maxBodySize)
                        ?: return@route tooLarge(maxBodySize)
                }
                val query = queryOf(servlet.queryString)
                // A servlet thread is the caller's own: waiting on it blocks no event loop.
                val response: ControlHttpResponse = runBlocking(Dispatchers.Default) {
                    http.handle(
                        ControlHttpRequest(request.method().name(), relative(request.path(), base), query, body),
                        security
                    )
                }
                ServletServerResponse.status(response.status)
                    .contentType(MediaType.parseMediaType(response.contentType))
                    .headers { headers -> response.headers.forEach(headers::set) }
                    .body(response.body)
            }
        }
    }

    /**
     * On a Spring Cloud Gateway host the gateway's global filter runs on its routes only, so the control routes would
     * never pass the pipeline and every call would be 401. A [PipelineWebFilter] limited to the control base path
     * authorizes them as on a WebFlux host; the gateway's own routes keep the gateway filter, and actuator endpoints
     * stay outside CoSec.
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
            context: ApplicationContext,
            bodies: ProblemBodies,
        ): ControlRoutesWebFilter = ControlRoutesWebFilter(
            PipelineWebFilter(
                pipeline,
                contributors.orderedStream().toList(),
                ReactiveClientIp.of(CoSecPipelineWebAutoConfiguration.clientIpOf(context, authorization)),
                CoSecPipelineWebAutoConfiguration.hostOf(authorization),
                // PIP-34: the control routes' refusals carry the host's body, as its gateway filter's do (DR1-C-7d).
                bodies,
            ),
            basePathOf(properties),
        )
    }

    internal companion object {
        private val EMPTY = ByteArray(0)

        /** At most [max] bytes of [request]'s body, or a [DataBufferLimitException] when it holds more. */
        fun readBody(request: ReactiveServerRequest, max: Long): Mono<ByteArray> =
            DataBufferUtils.join(
                request.body(BodyExtractors.toDataBuffers()),
                max.coerceAtMost(Int.MAX_VALUE - 1L).toInt()
            )
                .map { buffer ->
                    try {
                        ByteArray(buffer.readableByteCount()).also(buffer::read)
                    } finally {
                        DataBufferUtils.release(buffer)
                    }
                }
                .defaultIfEmpty(EMPTY)

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
            .body(tooLargeProblem(max))

        /** The problem of a body larger than [max] bytes (CTL-41), on either stack. */
        fun tooLargeProblem(max: Long): Map<String, Any> = mapOf(
            "type" to ProblemType.INVALID_REQUEST.uri,
            "title" to ProblemType.INVALID_REQUEST.title,
            "status" to ProblemType.INVALID_REQUEST.status,
            "code" to ProblemType.INVALID_REQUEST.code,
            "detail" to "The request body is larger than the control API reads ($max bytes) (CTL-41).",
        )

        fun basePathOf(properties: ControlProperties): String = "/" + properties.http.basePath.trim('/')

        /** The parameters of [queryString], decoded as a servlet decodes them, the first value of each name. */
        fun queryOf(queryString: String?): Map<String, String> {
            if (queryString.isNullOrEmpty()) {
                return emptyMap()
            }
            val query = LinkedHashMap<String, String>()
            for (pair in queryString.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val name = if (separator < 0) pair else pair.substring(0, separator)
                val value = if (separator < 0) "" else pair.substring(separator + 1)
                // A pair that does not decode is left out, as a servlet container leaves it out of the parameters.
                val decoded = try {
                    URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                query.putIfAbsent(decoded.first, decoded.second)
            }
            return query
        }

        /**
         * Where this version of the API is served: `{base-path}/v1`. The version is fixed here, not part of
         * `base-path`, so moving the API never drops it.
         */
        fun apiRootOf(properties: ControlProperties): String = basePathOf(properties) + "/" + API_VERSION

        const val API_VERSION = "v1"

        fun relative(path: String, base: String): String = path.removePrefix(base).ifEmpty { "/" }
    }
}
