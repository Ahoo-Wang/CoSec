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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.webflux.OrderedCorsWebFilter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * `STA-5`: when the application declares a `CorsConfigurationSource`, the starter registers a CORS filter immediately
 * before the pipeline's, on Spring MVC (Tomcat) and on WebFlux (Reactor Netty): a preflight is answered by it and never
 * decided, and a request that is not a preflight is decided as before. Without the bean nothing is registered, and a
 * preflight is decided as `http:OPTIONS` for an anonymous caller.
 */
class CorsWiringTest {
    @RestController
    class Pings {
        @GetMapping("/public/ping")
        fun ping(): ResponseEntity<String> = ResponseEntity.ok("pong")

        @GetMapping("/orders/1")
        fun order(): ResponseEntity<String> = ResponseEntity.ok("order")
    }

    /** The pipeline of the suite, counting what reaches it: a preflight the CORS filter answers is not counted. */
    class CountingGate(private val gate: RequestGate) : RequestGate {
        val evaluated = AtomicInteger()

        override suspend fun evaluate(facts: HttpFacts): Outcome {
            evaluated.incrementAndGet()
            return gate.evaluate(facts)
        }
    }

    private fun source() = UrlBasedCorsConfigurationSource().apply {
        registerCorsConfiguration(
            "/**",
            CorsConfiguration().apply {
                addAllowedOrigin(ORIGIN)
                addAllowedMethod("*")
                addAllowedHeader("*")
                addExposedHeader("CoSec-Request-Id")
            },
        )
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        TomcatServletWebServerAutoConfiguration::class,
        DispatcherServletAutoConfiguration::class,
        WebMvcAutoConfiguration::class,
        CoSecPipelineWebAutoConfiguration::class,
    )
    @EnableConfigurationProperties(RuntimeAuthorizationProperties::class)
    open class Mvc {
        /** The gateway filter's auto-configuration, on this classpath, wants a [Pipeline]; the servlet filter takes the primary gate. */
        @Bean
        open fun pipeline(): Pipeline = FIXTURE.pipeline

        @Bean
        @Primary
        open fun countingGate(): RequestGate = GATE

        @Bean
        open fun pings() = Pings()
    }

    @Configuration(proxyBeanMethods = false)
    open class MvcWithSource : Mvc() {
        @Bean
        open fun corsConfigurationSource(): org.springframework.web.cors.CorsConfigurationSource = CorsWiringTest().source()
    }

    @Configuration(proxyBeanMethods = false)
    open class MvcWithTwoSources : Mvc() {
        @Bean
        open fun first(): org.springframework.web.cors.CorsConfigurationSource = CorsWiringTest().source()

        @Bean
        open fun second(): org.springframework.web.cors.CorsConfigurationSource = CorsWiringTest().source()
    }

    @Configuration(proxyBeanMethods = false)
    open class MvcWithOwnFilter : Mvc() {
        @Bean
        open fun corsConfigurationSource(): org.springframework.web.cors.CorsConfigurationSource = CorsWiringTest().source()

        @Bean
        open fun ownCorsFilter(): CorsFilter = CorsFilter(CorsWiringTest().source())
    }

    private fun servlet(source: Class<*>, block: (Int, ConfigurableApplicationContext) -> Unit) {
        val context = SpringApplicationBuilder(source).web(WebApplicationType.SERVLET)
            .properties("server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off")
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> {
                    it.environment.propertySources.remove(GATEWAY_PROPERTIES)
                },
            )
            .run()
        context.use { block((it as WebServerApplicationContext).webServer!!.port, it) }
    }

    @EnableWebFlux
    @Configuration(proxyBeanMethods = false)
    open class FluxApp {
        @Bean
        open fun pipeline(): RequestGate = GATE

        @Bean
        open fun pings() = Pings()
    }

    private fun reactive(withSource: Boolean, block: (Int, ConfigurableApplicationContext) -> Unit) {
        val runner = ReactiveWebApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_FILTER))
            .withConfiguration(AutoConfigurations.of(CoSecPipelineWebAutoConfiguration::class.java))
            .withUserConfiguration(FluxApp::class.java)
            .withBean(RuntimeAuthorizationProperties::class.java, { RuntimeAuthorizationProperties() })
        val configured = if (withSource) {
            runner.withBean(
                org.springframework.web.cors.reactive.CorsConfigurationSource::class.java,
                {
                    org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource().apply {
                        registerCorsConfiguration("/**", cors())
                    }
                },
            )
        } else {
            runner
        }
        configured.run { context ->
            context.startupFailure.assert().isNull()
            val server = NettyReactiveWebServerFactory(0).getWebServer(
                WebHttpHandlerBuilder.applicationContext(context.sourceApplicationContext).build(),
            )
            server.start()
            try {
                block(server.port, context.sourceApplicationContext as ConfigurableApplicationContext)
            } finally {
                server.stop()
            }
        }
    }

    private fun cors() = CorsConfiguration().apply {
        addAllowedOrigin(ORIGIN)
        addAllowedMethod("*")
        addAllowedHeader("*")
        addExposedHeader("CoSec-Request-Id")
    }

    private fun send(port: Int, request: String): RawResponse = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        RawResponse.parse(socket.getInputStream().readBytes())
    }

    private fun preflight(port: Int, path: String, origin: String = ORIGIN) = send(
        port,
        "OPTIONS $path HTTP/1.1\r\nHost: localhost\r\nOrigin: $origin\r\nAccess-Control-Request-Method: GET\r\n" +
            "Access-Control-Request-Headers: authorization,cosec-device-id\r\nConnection: close\r\n\r\n",
    )

    private fun options(port: Int, path: String) =
        send(port, "OPTIONS $path HTTP/1.1\r\nHost: localhost\r\nOrigin: $ORIGIN\r\nConnection: close\r\n\r\n")

    private fun get(port: Int, path: String, origin: String = ORIGIN) =
        send(port, "GET $path HTTP/1.1\r\nHost: localhost\r\nOrigin: $origin\r\nConnection: close\r\n\r\n")

    /** The cells that matter: what the pipeline sees, and what a browser's script gets back. */
    private fun withSource(port: Int) {
        GATE.evaluated.set(0)
        val answered = preflight(port, "/orders/1")
        answered.status.assert().describedAs(answered.toString()).isEqualTo(200)
        answered.header("access-control-allow-origin").assert().isEqualTo(ORIGIN)
        GATE.evaluated.get().assert().describedAs("a preflight is not decided").isZero()

        val bare = options(port, "/orders/1")
        bare.status.assert().describedAs("an OPTIONS that is no preflight is decided: $bare").isEqualTo(401)
        GATE.evaluated.get().assert().isEqualTo(1)

        val refused = get(port, "/orders/1")
        refused.status.assert().describedAs("a refusal is decided as before: $refused").isEqualTo(401)
        refused.header("access-control-allow-origin").assert().describedAs("with the CORS headers").isEqualTo(ORIGIN)
        refused.header("access-control-expose-headers").assert().contains("CoSec-Request-Id")
        refused.header("cosec-request-id").assert().isNotNull()
        GATE.evaluated.get().assert().isEqualTo(2)

        val stranger = get(port, "/public/ping", "https://stranger.example")
        stranger.status.assert().describedAs("an origin the source does not allow: $stranger").isEqualTo(403)
        stranger.header("cosec-request-id").assert().describedAs("refused before the pipeline").isNull()
        GATE.evaluated.get().assert().describedAs("not decided").isEqualTo(2)

        val permitted = get(port, "/public/ping")
        permitted.status.assert().isEqualTo(200)
        permitted.header("access-control-allow-origin").assert().isEqualTo(ORIGIN)
        GATE.evaluated.get().assert().isEqualTo(3)
    }

    private fun withoutSource(port: Int) {
        GATE.evaluated.set(0)
        val answered = preflight(port, "/orders/1")
        answered.status.assert()
            .describedAs("decided as http:OPTIONS for an anonymous caller: $answered")
            .isEqualTo(401)
        answered.header("access-control-allow-origin").assert().isNull()
        GATE.evaluated.get().assert().isEqualTo(1)
    }

    private fun registeredCorsFilters(context: ConfigurableApplicationContext) =
        context.getBeansOfType(FilterRegistrationBean::class.java).values.filter { it.filter is CorsFilter }

    @Test
    fun `Spring MVC with a source answers a preflight before the pipeline and decides everything else`() {
        servlet(MvcWithSource::class.java) { port, context ->
            registeredCorsFilters(context).assert().hasSize(1)
            withSource(port)
        }
    }

    @Test
    fun `Spring MVC without a source decides a preflight as before and registers no CORS filter`() {
        servlet(Mvc::class.java) { port, context ->
            registeredCorsFilters(context).assert().isEmpty()
            withoutSource(port)
        }
    }

    @Test
    fun `Spring MVC with two sources fails startup and says to declare a CorsFilter`() {
        val failure = assertThrows<Exception> { servlet(MvcWithTwoSources::class.java) { _, _ -> } }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message.orEmpty() }
            .any { it.contains("STA-5: 2 CorsConfigurationSource beans") }.assert().isTrue()
    }

    /** The return types of a class's methods; a class whose optional dependency is missing from the classpath has none to read. */
    private fun declaredReturnTypes(type: Class<*>): List<Class<*>> = try {
        type.declaredMethods.map { it.returnType }
    } catch (_: NoClassDefFoundError) {
        emptyList()
    }

    /**
     * A gateway host: Spring Cloud Gateway's `globalcors` is properties of its own handler mapping, no bean declares a source
     * in its auto-configuration, and the starter's WebFlux filter is not wired where the gateway filter is.
     */
    @Test
    fun `a gateway host gets no CORS filter of the starter, with or without a source`() {
        val gateway = org.springframework.cloud.gateway.config.GatewayAutoConfiguration::class.java
        (listOf(gateway) + gateway.declaredClasses).flatMap { declaredReturnTypes(it) }
            .filter {
                org.springframework.web.cors.reactive.CorsConfigurationSource::class.java.isAssignableFrom(it) ||
                    org.springframework.web.cors.CorsConfigurationSource::class.java.isAssignableFrom(it)
            }
            .assert().describedAs("Spring Cloud Gateway declares no CorsConfigurationSource bean").isEmpty()
        val source = { org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource() }
        listOf(false, true).forEach { declared ->
            val runner = ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoSecPipelineWebAutoConfiguration::class.java))
                .withBean(RequestGate::class.java, { GATE })
                .withBean(Pipeline::class.java, { FIXTURE.pipeline })
                .withBean(RuntimeAuthorizationProperties::class.java, { RuntimeAuthorizationProperties() })
            val configured = if (declared) {
                runner.withBean(org.springframework.web.cors.reactive.CorsConfigurationSource::class.java, source)
            } else {
                runner
            }
            configured.run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(OrderedCorsWebFilter::class.java).assert().isEmpty()
                context.getBeansOfType(me.ahoo.cosec.gateway.PipelineGatewayFilter::class.java).assert().hasSize(1)
            }
        }
    }

    @Test
    fun `Spring MVC leaves the application's own CorsFilter alone`() {
        servlet(MvcWithOwnFilter::class.java) { _, context ->
            context.getBeansOfType(CorsFilter::class.java).keys.assert().containsExactly("ownCorsFilter")
            registeredCorsFilters(context).assert().isEmpty()
        }
    }

    @Test
    fun `WebFlux with a source answers a preflight before the pipeline and decides everything else`() {
        reactive(withSource = true) { port, context ->
            context.getBeansOfType(OrderedCorsWebFilter::class.java).assert().hasSize(1)
            withSource(port)
        }
    }

    @Test
    fun `WebFlux without a source decides a preflight as before and registers no CORS filter`() {
        reactive(withSource = false) { port, context ->
            context.getBeansOfType(OrderedCorsWebFilter::class.java).assert().isEmpty()
            withoutSource(port)
        }
    }

    private companion object {
        const val ORIGIN = "https://app.example"
        val FIXTURE = ParityFixture()
        val GATE = CountingGate(FIXTURE.pipeline)

        /** The property source of `GatewayEnvironmentPostProcessor`. */
        const val GATEWAY_PROPERTIES = "gateway-properties"
        const val GATEWAY_FILTER = "org.springframework.cloud.gateway.filter.GlobalFilter"
    }
}
