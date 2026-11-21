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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.mock.env.MockEnvironment
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * `CTL-58`, `CTL-59`: the control plane's service port (a listener of its own, serving the control API's service profile
 * and nothing else, closed with its connections when the application stops), the switch that serves no control route, and
 * the maintenance passes that every instance runs. The refusals are `STA-9`'s and `CTL-58`'s.
 */
class ControlServicePortTest {
    private val runner = ReactiveWebApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                HttpHandlerAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
                CoSecControlRoutesAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            "cosec.ip2region.enabled=false",
            "cosec.authorization.routes.location=",
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })

    /** The status line of a `GET` to [path] on an open [socket], read from its response head. */
    private fun statusOf(socket: Socket, path: String): Int {
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            flush()
        }
        val head = StringBuilder()
        val input = socket.getInputStream()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            check(next >= 0) { "the connection closed before a response: [$head]" }
            head.append(next.toChar())
        }
        // The body is part of the response: left unread, it would be what the next read of the connection returns.
        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(length) { input.read() }
        return head.toString().substringBefore("\r\n").split(' ')[1].toInt()
    }

    @Test
    fun `the service port listens apart, serves nothing but the control API, and closes its connections when it stops`() {
        runner.withPropertyValues("${ControlServicePort.KEY}=0").run { context ->
            context.startupFailure.assert().isNull()
            val listener = context.getBean(ControlServiceListener::class.java)
            listener.boundPort.assert().isPositive()
            val socket = Socket("127.0.0.1", listener.boundPort).apply { soTimeout = SECONDS_5 }
            socket.use {
                // Not the control API: a 404 before the gate or any route of the application is asked.
                statusOf(it, "/anything").assert().isEqualTo(404)
                statusOf(it, "/cosec/control-not/v1").assert().isEqualTo(404)
                // The control API's own base path reaches the gate: it answers, whatever it answers.
                statusOf(it, "/cosec/control/v1/documents/tenant:acme").assert().isNotEqualTo(404)
                // The connection is still open, a keep-alive one: stopping must not leave it standing.
                context.close()
                val closed = try {
                    it.getInputStream().read() == -1
                } catch (e: SocketTimeoutException) {
                    false.also { _ -> e.message }
                }
                closed.assert().describedAs(
                    "the keep-alive connection was closed when the service port stopped"
                ).isTrue()
            }
            listener.isRunning.assert().isFalse()
        }
    }

    @Test
    fun `a service port beside the application's, the management port or a disabled control API is refused`() {
        fun message(vararg properties: String): String = assertThrownBy<IllegalStateException> {
            ControlServicePort.check(
                MockEnvironment().apply {
                    properties.forEach {
                        withProperty(
                            it.substringBefore('='),
                            it.substringAfter('=')
                        )
                    }
                }
            )
        }.actual().message.orEmpty()
        message("${ControlServicePort.KEY}=8080").assert().contains("must be its own")
        message("${ControlServicePort.KEY}=9000", "server.port=9000").assert().contains("must be its own")
        message("${ControlServicePort.KEY}=9001", "management.server.port=9001").assert().contains("must be its own")
        message("${ControlServicePort.KEY}=8082", "cosec.control.http.enabled=false").assert()
            .contains("serves no control route")
        // The gateway's statement says only the gateway can reach the host: a service port says services can.
        message(
            "${ControlServicePort.KEY}=8082",
            "cosec.pipeline.identity.only-gateway-can-reach=true",
        ).assert().contains(
            "only-trusted-callers-can-reach=true"
        ).contains("COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH")
        // What is accepted: a port of its own with the trusted callers' statement, or none stated here (STA-9 asks).
        ControlServicePort.check(
            MockEnvironment().withProperty(ControlServicePort.KEY, "8082")
                .withProperty("cosec.pipeline.identity.only-trusted-callers-can-reach", "true")
        )
        ControlServicePort.check(MockEnvironment().withProperty(ControlServicePort.KEY, "8082"))
        ControlServicePort.check(
            MockEnvironment().withProperty("cosec.pipeline.identity.only-gateway-can-reach", "true")
        )
    }

    @Test
    fun `a service port with no WebFlux server to serve it fails startup naming what to do`() {
        val environment = MockEnvironment().withProperty(ControlServicePort.KEY, "8082")
        assertThrownBy<IllegalStateException> {
            ControlServicePort.requireListener(environment, DefaultListableBeanFactory())
        }.hasMessageContaining("WebFlux only")
        ControlServicePort.requireListener(MockEnvironment(), DefaultListableBeanFactory())
    }

    @Test
    fun `cosec control http enabled false serves no control route, and the control plane stays for what embeds it`() {
        runner.run { context ->
            context.containsBean("cosecControlRoutes").assert().isTrue()
        }
        runner.withPropertyValues("cosec.control.http.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            context.containsBean("cosecControlRoutes").assert().isFalse()
            context.getBeansOfType(ControlHttp::class.java).assert().hasSize(1)
            context.containsBean("cosecControlPlane").assert().isTrue()
        }
    }

    @Test
    fun `a request with no server behind it has no peer, and the service port filter lets it through`() {
        val exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/anything").build()
        )
        ControlPeer.of(exchange).assert().isNull()
        runner.withPropertyValues("${ControlServicePort.KEY}=0").run { context ->
            val listener = context.getBean(ControlServiceListener::class.java)
            var passed = false
            // A request that carries no local address is on no port of this listener: it is not the filter's to refuse.
            ServicePortFilter(listener, "/cosec/control").filter(exchange) {
                passed = true
                reactor.core.publisher.Mono.empty()
            }.block()
            passed.assert().isTrue()
            ServicePortFilter(
                listener,
                "/cosec/control"
            ).order.assert().isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        }
    }

    /**
     * The peer is the connection's: a Reactor Netty server that reads `X-Forwarded-For` and `Forwarded`
     * (`server.forward-headers-strategy=native` configures that on the application's) shows the caller's claim as the
     * request's remote address, and the control routes still read the address the TCP stack saw.
     */
    @Test
    fun `the peer of a control request is the connection's, beneath the forwarded header handling`() {
        val seen = java.util.concurrent.atomic.AtomicReference<Pair<String?, String?>>()
        // First of the chain: the pipeline's own filter answers a request nothing permitted before the later ones run.
        val probe = object : org.springframework.web.server.WebFilter, org.springframework.core.Ordered {
            override fun filter(
                exchange: org.springframework.web.server.ServerWebExchange,
                chain: org.springframework.web.server.WebFilterChain,
            ): reactor.core.publisher.Mono<Void> {
                seen.set(exchange.request.remoteAddress?.hostString to ControlPeer.of(exchange))
                return chain.filter(exchange)
            }

            override fun getOrder(): Int = org.springframework.core.Ordered.HIGHEST_PRECEDENCE
        }
        runner.withBean("peerProbe", org.springframework.web.server.WebFilter::class.java, { probe }).run { context ->
            val forwarding = reactor.netty.http.server.HttpServer.create().port(0).forwarded(true)
                .handle(
                    org.springframework.http.server.reactive.ReactorHttpHandlerAdapter(
                        context.getBean(org.springframework.http.server.reactive.HttpHandler::class.java)
                    )
                ).bindNow()
            try {
                Socket("127.0.0.1", forwarding.port()).use { socket ->
                    socket.soTimeout = SECONDS_5
                    socket.getOutputStream().apply {
                        write(
                            ("GET /cosec/control/probe HTTP/1.1\r\nHost: localhost\r\nX-Forwarded-For: 203.0.113.9\r\n\r\n")
                                .encodeToByteArray()
                        )
                        flush()
                    }
                    socket.getInputStream().read()
                }
            } finally {
                forwarding.disposeNow()
            }
            val (rewritten, peer) = seen.get()
            // The request's own address is what the caller said; the peer is what the connection was.
            rewritten.assert().isEqualTo("203.0.113.9")
            peer.assert().isEqualTo("127.0.0.1")
        }
    }

    /** `CTL-72`: one startup line says what the control plane computed and from what. */
    @Test
    fun `a host that runs the control plane says once what it derived`() {
        runner.withConfiguration(
            AutoConfigurations.of(CoSecControlServicePortAutoConfiguration::class.java)
        ).run { context ->
            context.containsBean("cosecDerivedControlSettingsNotice").assert().isTrue()
        }
        runner.withPropertyValues("cosec.control.shadow.min-duration=5s").run { context ->
            context.getBean(
                ControlProperties::class.java
            ).shadow.minDuration.assert().isEqualTo(java.time.Duration.ofSeconds(5))
        }
    }

    /**
     * `CTL-59`: every instance with the control plane on runs the maintenance passes, whatever else is configured, and
     * nothing turns them off. Two instances over one store run each pass once per interval, by their leases:
     * `ControlMaintenanceTest` (the shadow lease, the reclamation lease) and `ControlReplicaScenario`, which stops the
     * replica that started a window and sees it closed by the other.
     */
    @Test
    fun `the maintenance passes run on every host that has the control plane, and no property turns them off`() {
        runner.run { context -> context.containsBean("cosecControlMaintenanceScheduler").assert().isTrue() }
        runner.withPropertyValues("cosec.control.http.enabled=false").run { context ->
            context.containsBean("cosecControlMaintenanceScheduler").assert().isTrue()
            context.containsBean("cosecControlPlane").assert().isTrue()
        }
        runner.withPropertyValues("${ControlServicePort.KEY}=0").run { context ->
            context.containsBean("cosecControlMaintenanceScheduler").assert().isTrue()
        }
    }

    private companion object {
        const val SECONDS_5 = 5_000
    }
}
