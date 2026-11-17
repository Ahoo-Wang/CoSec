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

package me.ahoo.cosec.spring.boot.realserver

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `STA-4`: on every port a host opens, nothing the application serves answers without passing CoSec. Each cell of the
 * tables below is an assertion against real servers (Reactor Netty for WebFlux, Tomcat for WebMvc), started the way an
 * application starts, with a separate `management.server.port` or with one port.
 *
 * Spring Boot decides what a management server serves: its child context inherits beans from the application's, and
 * CoSec decides nothing about it. That is why these tests exist. On WebFlux the child collects the application's
 * `WebFilter`s, so CoSec's pipeline stands in front of it, and it also collects the application's `RouterFunction`s
 * (the application's functional routes and the control API are served there, behind CoSec). On WebMvc the filter
 * registration never reaches the management server, and the child serves actuator's endpoints and nothing of the
 * application's (a controller, a functional route and the control API are Spring's 404). On a gateway host Spring
 * Cloud Gateway itself leaves a request on a different management port alone, so the gateway's routes are not served
 * there; the control API is, behind the pipeline's filter limited to its base path. **A Spring Boot upgrade that
 * changes what a child context inherits shows up here first**: a cell that moves is either an application route that
 * became reachable outside CoSec, or a pipeline that no longer fronts the management server.
 */
class ManagementPortTest {
    @Test
    fun `WebFlux keeps its management server under the pipeline and serves the application's routes only behind it`() {
        val cells = run(Stack.REACTIVE, separate = true)
        // The application port.
        cells.before.app.assert().isEqualTo(GATED_BEFORE)
        cells.after.app.assert().isEqualTo(GATED_AFTER)
        // The management port: CoSec's pipeline too, the actuator probes and the application's functional route alike.
        cells.before.management.assert().isEqualTo(GATED_BEFORE)
        cells.after.management.assert().isEqualTo(GATED_AFTER)
        // A controller is served nowhere but the application's server (Spring's 404 behind the pipeline).
        cells.managementController.assert().isEqualTo(404)
    }

    @Test
    fun `WebMvc leaves its management server outside CoSec and serves nothing of the application there`() {
        val cells = run(Stack.SERVLET, separate = true)
        cells.before.app.assert().isEqualTo(GATED_BEFORE)
        cells.after.app.assert().isEqualTo(GATED_AFTER)
        // Spring's own answers: liveness is up, readiness follows the snapshot (503, then 200).
        cells.before.management.assert().isEqualTo(Probes(200, 503, 404, 404))
        cells.after.management.assert().isEqualTo(Probes(200, 200, 404, 404))
        cells.managementController.assert().isEqualTo(404)
    }

    @Test
    fun `a gateway leaves its management server outside CoSec too, and gates its own routes and the control API there as on the application port`() {
        val cells = run(Stack.GATEWAY, separate = true)
        // The application port: the gateway's route is CoSec's, and so is the control API. Actuator is not served there
        // (404): the gateway's filter fronts its routes, not the probes of a host that has a management port.
        cells.before.app.assert().isEqualTo(Probes(404, 404, 503, 503))
        cells.after.app.assert().isEqualTo(Probes(404, 404, 401, 401))
        // The management port: actuator's endpoints are Spring's, the gateway's route is not served, and the control
        // API, which the pipeline's filter limited to its base path fronts, is CoSec's.
        cells.before.management.assert().isEqualTo(Probes(200, 503, 404, 503))
        cells.after.management.assert().isEqualTo(Probes(200, 200, 404, 401))
    }

    @Test
    fun `the two stacks answer alike on the application port`() {
        val reactive = run(Stack.REACTIVE, separate = true)
        val servlet = run(Stack.SERVLET, separate = true)
        reactive.before.app.assert().isEqualTo(servlet.before.app)
        reactive.after.app.assert().isEqualTo(servlet.after.app)
    }

    @Test
    fun `with one port nothing changes, the probes are requests like any other`() {
        for (stack in listOf(Stack.REACTIVE, Stack.SERVLET)) {
            val cells = run(stack, separate = false)
            cells.before.app.assert().describedAs(stack.name).isEqualTo(GATED_BEFORE)
            cells.after.app.assert().describedAs(stack.name).isEqualTo(GATED_AFTER)
        }
    }

    private fun run(stack: Stack, separate: Boolean): Cells {
        val context = start(stack, separate)
        context.use {
            val app = context.environment.getRequiredProperty("local.server.port").toInt()
            val management = if (separate) {
                context.environment.getRequiredProperty(
                    "local.management.port"
                ).toInt()
            } else {
                app
            }
            val before = Observation(probes(app, stack.route), probes(management, stack.route))
            publish(context)
            // The platform document is installed once the application's own public route answers (a gateway has none:
            // once it stops answering 503).
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (if (stack.gateway) get(app, stack.route) == 503 else get(app, "/hello") != 200) {
                check(System.nanoTime() < deadline) { "The platform document was not installed on ${stack.name}." }
                Thread.sleep(50)
            }
            // Readiness follows the snapshot on its own timer: it turns to accepting traffic a moment after the install.
            while (get(management, "/actuator/health/readiness") == 503) {
                check(System.nanoTime() < deadline) { "Readiness did not follow the document on ${stack.name}." }
                Thread.sleep(50)
            }
            val after = Observation(probes(app, stack.route), probes(management, stack.route))
            return Cells(
                Phase(before.app, before.management),
                Phase(after.app, after.management),
                get(management, "/hello"),
            )
        }
    }

    /** A real application of [stack]: Spring Boot's own server and management child, CoSec's starter, in-memory CoCache. */
    private fun start(stack: Stack, separate: Boolean): ConfigurableApplicationContext {
        val hidden = buildList {
            if (!stack.gateway) add(GATEWAY_GLOBAL_FILTER)
            // The modules the test classpath carries for other tests; none is wanted here.
            addAll(
                listOf("me.ahoo.cosec.kafka", "me.ahoo.cosec.social", "me.ahoo.cosec.openapi", "me.ahoo.cosec.cosid")
            )
            addAll(listOf("me.ahoo.cosec.opentelemetry", "me.ahoo.cosec.micrometer", "me.ahoo.cosec.ip2region"))
            // Spring Boot's management configurations of two servers of one kind clash when both are on the classpath
            // (the test classpath has them all, for the real-server tests): keep Tomcat for WebMvc, Netty for WebFlux.
            addAll(listOf("org.eclipse.jetty", "io.undertow"))
            if (!stack.servlet) {
                addAll(listOf("org.apache.catalina", "org.apache.tomcat", "org.apache.coyote"))
            }
        }
        val properties = buildList {
            add("server.port=0")
            add("cocache.backend=memory")
            add("cosec.jwt.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_")
            add("cosec.authorization.routes.location=")
            add("cosec.authorization.snapshot.revalidate-interval=1s")
            add("management.endpoint.health.probes.enabled=true")
            add("spring.main.banner-mode=off")
            if (stack.gateway) {
                // A gateway host: one route, to a backend nothing listens on (the requests the tests send never get there).
                add("spring.cloud.gateway.server.webflux.routes[0].id=token")
                add("spring.cloud.gateway.server.webflux.routes[0].uri=http://localhost:1")
                add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/token")
            } else {
                // Spring Cloud Gateway is on the test classpath for other tests: none of its configurations is wanted.
                add("spring.autoconfigure.exclude=" + gatewayConfigurations().joinToString(","))
            }
            // A port Spring Cloud Gateway can compare with: it leaves a request on a management port that differs from
            // the application's alone, which it cannot do for the random port 0.
            if (separate) add("management.server.port=${freePort()}")
        }
        return SpringApplicationBuilder(if (stack.gateway) GatewayApplication::class.java else Application::class.java)
            .web(if (stack.servlet) WebApplicationType.SERVLET else WebApplicationType.REACTIVE)
            .resourceLoader(DefaultResourceLoader(FilteredClassLoader(*hidden.toTypedArray())))
            .properties(*properties.toTypedArray())
            .run()
    }

    private fun gatewayConfigurations(): List<String> =
        ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).asSequence()
            .filter { it.startsWith("org.springframework.cloud.gateway.") }
            .toList()

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun publish(context: ConfigurableApplicationContext) {
        val revisions = context.getBean(Revisions::class.java)
        runBlocking {
            revisions.publish(
                SnapshotKey.Platform,
                revisions.head(SnapshotKey.Platform),
                PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", PLATFORM.encodeToByteArray()),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
            )
        }
    }

    private fun probes(port: Int, route: String): Probes = Probes(
        get(port, "/actuator/health/liveness"),
        get(port, "/actuator/health/readiness"),
        get(port, route),
        get(port, "$CONTROL/documents/platform"),
    )

    private fun get(port: Int, path: String): Int =
        CLIENT.send(
            HttpRequest.newBuilder(
                URI.create("http://localhost:$port$path")
            ).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    /** Status codes of liveness, readiness, the application's functional route, the control API: anonymous callers. */
    private data class Probes(val liveness: Int, val readiness: Int, val functionalRoute: Int, val controlApi: Int)

    private data class Observation(val app: Probes, val management: Probes)

    private data class Phase(val app: Probes, val management: Probes)

    private data class Cells(val before: Phase, val after: Phase, val managementController: Int)

    private enum class Stack(val servlet: Boolean, val gateway: Boolean = false, val route: String = "/orders/1") {
        REACTIVE(false),
        SERVLET(true),
        GATEWAY(false, gateway = true, route = "/token"),
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ReactiveRoutes::class, ServletRoutes::class)
    class Application {
        @Bean
        fun demoController(): DemoController = DemoController("hello")
    }

    /** A gateway host: CoSec authorizes the gateway's routes and the control API, and nothing else (`STA-2`). */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class GatewayApplication

    @RestController
    class DemoController(private val greeting: String) {
        @GetMapping("/hello")
        fun hello(): String = greeting
    }

    /** The application's own functional routes (WebFlux.fn on the reactive stack, WebMvc.fn on the servlet one). */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.REACTIVE,
    )
    class ReactiveRoutes {
        @Bean
        fun reactiveRoutes():
            org.springframework.web.reactive.function.server.RouterFunction<org.springframework.web.reactive.function.server.ServerResponse> =
            org.springframework.web.reactive.function.server.RouterFunctions.route()
                .GET("/orders/{id}") {
                    org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("fn-order")
                }.build()
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET,
    )
    class ServletRoutes {
        @Bean
        fun servletRoutes():
            org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse> =
            org.springframework.web.servlet.function.RouterFunctions.route()
                .GET("/orders/{id}") {
                    org.springframework.web.servlet.function.ServerResponse.ok().body("fn-order")
                }.build()
    }

    private companion object {
        const val CONTROL = "/cosec/control/v1"
        val CLIENT: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        /** `/hello` is permitted to anyone; nothing else is. */
        const val PLATFORM = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"policies":[{"id":"public","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/hello"}]}],""" +
            """"bindings":[{"id":"public","subject":"*","app":"*","space":"*","policy":"public"}]}"""

        /** CoSec's answer before a platform document exists: every request, the probes included, is 503. */
        val GATED_BEFORE = Probes(503, 503, 503, 503)

        /** After it: anonymous callers are 401 wherever no statement permits the path. */
        val GATED_AFTER = Probes(401, 401, 401, 401)
    }
}
