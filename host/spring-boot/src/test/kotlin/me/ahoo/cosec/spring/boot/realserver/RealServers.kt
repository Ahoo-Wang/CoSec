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

package me.ahoo.cosec.spring.boot.starter.realserver

import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.gateway.IdentityStripFilter
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.RequestGate
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import me.ahoo.cosec.servlet.PipelineServletFilter
import me.ahoo.cosec.webflux.PipelineWebFilter
import org.springframework.boot.autoconfigure.web.WebProperties
import org.springframework.boot.jetty.autoconfigure.JettyServerProperties
import org.springframework.boot.jetty.autoconfigure.JettyWebServerFactoryCustomizer
import org.springframework.boot.jetty.reactive.JettyReactiveWebServerFactory
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerFactoryCustomizer
import org.springframework.boot.reactor.netty.autoconfigure.NettyServerProperties
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.tomcat.autoconfigure.TomcatWebServerFactoryCustomizer
import org.springframework.boot.tomcat.reactive.TomcatReactiveWebServerFactory
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.boot.web.server.reactive.ConfigurableReactiveWebServerFactory
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.env.Environment
import org.springframework.http.server.PathContainer
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebHandler
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.util.EnumSet

/**
 * The servers CoSec runs on, each built by Spring Boot's own factory and customized by Spring Boot's own customizer for
 * the given properties (`server.forward-headers-strategy`, `server.tomcat.remoteip.*`, ...), so a request reaches the
 * adapter exactly as it would in an application: what the server decoded, rewrote or kept is what the adapter reads.
 */
enum class Stack(val servlet: Boolean) {
    TOMCAT(true),
    JETTY(true),
    NETTY(false),
    TOMCAT_REACTIVE(false),
    JETTY_REACTIVE(false),

    /** The gateway's filter over Reactor Netty, behind a route match that names an `lb://` route. */
    GATEWAY(false),
    ;

    override fun toString(): String = name.lowercase()
}

/** What the handler, or the gateway's route, received of a permitted request; written as its response body. */
internal const val SERVED = "served"

/** A server of [stack] in front of [gate], running until closed. */
internal class RealServer(
    val stack: Stack,
    gate: RequestGate,
    remoteIp: RealServerRemoteIp,
    environment: Environment = MockEnvironment(),
    serverProperties: ServerProperties = ServerProperties(),
    contextPath: String = "",
) : AutoCloseable {
    private val server: WebServer = start(stack, gate, remoteIp, environment, serverProperties, contextPath)

    val port: Int get() = server.port

    /** Sends [request], raw, on a new connection, and reads the whole response. */
    fun send(request: String): RawResponse = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = TIMEOUT_MILLIS
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        RawResponse.parse(socket.getInputStream().readBytes())
    }

    override fun close() = server.stop()

    private companion object {
        const val TIMEOUT_MILLIS = 10_000

        fun start(
            stack: Stack,
            gate: RequestGate,
            remoteIp: RealServerRemoteIp,
            environment: Environment,
            serverProperties: ServerProperties,
            contextPath: String,
        ): WebServer {
            val factory: Any = when (stack) {
                Stack.TOMCAT -> TomcatServletWebServerFactory(
                    0
                ).also { tomcat(environment, serverProperties).customize(it) }
                Stack.JETTY -> JettyServletWebServerFactory(
                    0
                ).also { jetty(environment, serverProperties).customize(it) }
                Stack.NETTY, Stack.GATEWAY ->
                    NettyReactiveWebServerFactory(0).also { netty(environment, serverProperties).customize(it) }
                Stack.TOMCAT_REACTIVE ->
                    TomcatReactiveWebServerFactory(0).also { tomcat(environment, serverProperties).customize(it) }
                Stack.JETTY_REACTIVE ->
                    JettyReactiveWebServerFactory(0).also { jetty(environment, serverProperties).customize(it) }
            }
            val server = if (factory is ConfigurableServletWebServerFactory) {
                factory.setContextPath(contextPath)
                val filter = PipelineServletFilter(gate, remoteIpResolver = remoteIp.servlet)
                factory.getWebServer({ context ->
                    context.addFilter("cosec", filter)
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*")
                    context.addServlet("app", EchoServlet()).addMapping("/*")
                })
            } else {
                check(contextPath.isEmpty()) { "A reactive server has no context path here." }
                val handler = WebHttpHandlerBuilder.webHandler(EchoHandler)
                    .filters { it.addAll(reactiveFilters(stack, gate, remoteIp)) }
                    .build()
                (factory as ConfigurableReactiveWebServerFactory).getWebServer(handler)
            }
            server.start()
            return server
        }

        fun reactiveFilters(stack: Stack, gate: RequestGate, remoteIp: RealServerRemoteIp): List<WebFilter> =
            if (stack == Stack.GATEWAY) {
                val pipeline = gate as Pipeline
                val strip = IdentityStripFilter()
                val filter = PipelineGatewayFilter(pipeline, remoteIpResolver = remoteIp.reactive)
                listOf(
                    // What RoutePredicateHandlerMapping does: the matched route on the exchange, before the global filters.
                    WebFilter { exchange, chain ->
                        exchange.attributes[GATEWAY_ROUTE_ATTR] = ROUTE
                        chain.filter(exchange)
                    },
                    WebFilter { exchange, chain ->
                        strip.filter(exchange) { stripped ->
                            filter.filter(stripped, GatewayFilterChain { routed -> chain.filter(routed) })
                        }
                    },
                )
            } else {
                listOf(PipelineWebFilter(gate, remoteIpResolver = remoteIp.reactive))
            }

        val ROUTE: Route = Route.async().id("backend").uri(URI.create("lb://backend")).predicate { true }.build()

        fun tomcat(environment: Environment, server: ServerProperties) =
            TomcatWebServerFactoryCustomizer(environment, server, TomcatServerProperties(), WebProperties())

        fun jetty(environment: Environment, server: ServerProperties) =
            JettyWebServerFactoryCustomizer(environment, server, JettyServerProperties())

        fun netty(environment: Environment, server: ServerProperties) =
            NettyReactiveWebServerFactoryCustomizer(environment, server, NettyServerProperties())
    }

    /** Writes what the servlet stacks' handlers received. */
    private class EchoServlet : HttpServlet() {
        override fun service(request: HttpServletRequest, response: HttpServletResponse) {
            response.contentType = "text/plain"
            response.writer.write(
                "$SERVED ${request.method} ${request.servletPath}${request.pathInfo.orEmpty()} " +
                    "identity=${request.getHeader("CoSec-Identity")}"
            )
        }
    }

    /** Writes what the reactive stacks' handlers, or the gateway's route, received. */
    private object EchoHandler : WebHandler {
        override fun handle(exchange: ServerWebExchange): Mono<Void> {
            val request = exchange.request
            // The path as Spring routes it: each segment decoded, without its path parameters.
            val path = request.path.pathWithinApplication().elements()
                .filterIsInstance<PathContainer.PathSegment>()
                .joinToString("/", prefix = "/") { it.valueToMatch() }
            val body = "$SERVED ${request.method.name()} $path " +
                "identity=${request.headers.getFirst("CoSec-Identity")}"
            exchange.response.headers.set("Content-Type", "text/plain")
            return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body.toByteArray())))
        }
    }
}

/** How the adapters under test read the client address: one resolver per kind of host request. */
internal class RealServerRemoteIp(
    val servlet: RemoteIpResolver<HttpServletRequest>,
    val reactive: RemoteIpResolver<ServerWebExchange>,
)

/** A response as the client read it off the wire. */
internal class RawResponse(val status: Int, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    override fun toString(): String = "$status $headers $body"

    companion object {
        private const val CRLF = "\r\n"

        fun parse(bytes: ByteArray): RawResponse {
            val text = String(bytes, Charsets.ISO_8859_1)
            val head = text.substringBefore(CRLF + CRLF)
            var body = text.substring(head.length + 4)
            val lines = head.split(CRLF)
            val status = lines.first().split(' ')[1].toInt()
            val headers = LinkedHashMap<String, MutableList<String>>()
            for (line in lines.drop(1)) {
                headers.getOrPut(line.substringBefore(':').trim().lowercase()) { ArrayList() } +=
                    line.substringAfter(':').trim()
            }
            if (headers["transfer-encoding"]?.any { it.equals("chunked", ignoreCase = true) } == true) {
                body = unchunk(body)
            }
            return RawResponse(status, headers, String(body.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
        }

        private fun unchunk(chunked: String): String {
            val out = ByteArrayOutputStream()
            var rest = chunked
            while (true) {
                val size = rest.substringBefore(CRLF).substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    return String(out.toByteArray(), Charsets.ISO_8859_1)
                }
                val start = rest.indexOf(CRLF) + 2
                out.write(rest.substring(start, start + size).toByteArray(Charsets.ISO_8859_1))
                rest = rest.substring(start + size + 2)
            }
        }
    }
}
