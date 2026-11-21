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
import me.ahoo.cosec.spring.boot.pipeline.ApplicationPort
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `CTL-69`: [ApplicationPort.serves] is true for a request with no local address, which only a mock exchange has. On
 * every reactive server CoSec supports (Reactor Netty, Tomcat and Jetty, the ones a WebFlux application runs on) a
 * request that arrived over a connection always carries the address it arrived on, and it is the port the server
 * announced when it bound. If a server stopped giving it, CoSec's own routes would answer on every port of the host, the
 * management port included, and this test is where that shows.
 */
class ApplicationPortTest {
    private enum class Server(val hidden: List<String>) {
        NETTY(listOf("org.eclipse.jetty", "org.apache.catalina", "org.apache.coyote", "org.apache.tomcat")),
        TOMCAT(listOf("reactor.netty", "org.eclipse.jetty")),
        JETTY(listOf("reactor.netty", "org.apache.catalina", "org.apache.coyote", "org.apache.tomcat")),
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    internal class Application {
        @Bean
        fun applicationPort(): ApplicationPort = ApplicationPort()

        /** Answers what the request carries: its local port, and whether CoSec's own routes would answer it. */
        @Bean
        fun address(port: ApplicationPort): RouterFunction<ServerResponse> = RouterFunctions.route()
            .GET("/address") { request ->
                val local = request.exchange().request.localAddress?.port
                ServerResponse.ok().bodyValue("$local ${port.serves(local)}")
            }.build()
    }

    @Test
    fun `a request over a connection carries the port the server bound, on Reactor Netty`() = carries(Server.NETTY)

    @Test
    fun `a request over a connection carries the port the server bound, on reactive Tomcat`() = carries(Server.TOMCAT)

    @Test
    fun `a request over a connection carries the port the server bound, on reactive Jetty`() = carries(Server.JETTY)

    private fun carries(server: Server) {
        val context = SpringApplicationBuilder(Application::class.java)
            .web(WebApplicationType.REACTIVE)
            .resourceLoader(DefaultResourceLoader(FilteredClassLoader(*(server.hidden + UNWANTED).toTypedArray())))
            .properties(
                "server.port=0",
                "cosec.enabled=false",
                "cocache.backend=memory",
                "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude=" + gatewayConfigurations().joinToString(","),
            )
            .run()
        context.use {
            val bound = it.environment.getRequiredProperty("local.server.port").toInt()
            val answer = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$bound/address")).timeout(Duration.ofSeconds(10))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            answer.statusCode().assert().describedAs(server.name).isEqualTo(200)
            // `null` is what a request with no local address reads as: never on a real connection.
            answer.body().assert().describedAs("${server.name}: the request's local port and whether it is served")
                .isEqualTo("$bound true")
        }
    }

    /** Spring Cloud Gateway is on the test classpath for other tests: none of its configurations is wanted here. */
    private fun gatewayConfigurations(): List<String> =
        ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).asSequence()
            .filter { it.startsWith("org.springframework.cloud.gateway.") }
            .toList()

    private companion object {
        /** What the test classpath carries for other tests, and this one does not want: only a server and a route. */
        val UNWANTED = listOf(
            GATEWAY_GLOBAL_FILTER,
            "me.ahoo.cosec.kafka",
            "me.ahoo.cosec.social",
            "me.ahoo.cosec.openapi",
            "me.ahoo.cosec.cosid",
            "me.ahoo.cosec.opentelemetry",
            "me.ahoo.cosec.micrometer",
            "me.ahoo.cosec.ip2region",
        )
        val CLIENT: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
