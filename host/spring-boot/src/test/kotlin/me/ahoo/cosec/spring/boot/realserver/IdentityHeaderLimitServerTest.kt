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

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.spring.boot.pipeline.IdentityHeaderLimit
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.util.unit.DataSize
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import java.net.Socket

private const val JETTY = "org.eclipse.jetty"
private val TOMCAT = arrayOf("org.apache.catalina", "org.apache.tomcat", "org.apache.coyote")
private const val NETTY_SERVER = "reactor.netty.http.server"

/**
 * `IDN-43` on real servers, whole applications: the request-header limit of a host that reads `CoSec-Identity` is the
 * one derived from the environment the application runs with, whatever put it in `gateway` mode and when. The host here
 * is put there by its own environment post-processor, unordered and added last ([LateGatewayMode]), as the first
 * application upgraded to 8.0.0-beta.9 is: a request whose identity header and other headers pass Spring Boot's 8 KB
 * reaches the gate on every server, Reactor Netty under WebFlux, Tomcat and Jetty under Spring MVC and under WebFlux.
 * An operator's limit stands, and a host that reads no identity keeps the server's own.
 */
@ExtendWith(OutputCaptureExtension::class)
class IdentityHeaderLimitServerTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class ReactiveApplication {
        @Bean
        fun hello(): RouterFunction<ServerResponse> =
            RouterFunctions.route().GET(PATH) { ServerResponse.ok().bodyValue("hello") }.build()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class ServletApplication {
        @Bean
        fun hello(): ServletRegistrationBean<HttpServlet> = ServletRegistrationBean(Hello, PATH)
    }

    private object Hello : HttpServlet() {
        private fun readResolve(): Any = Hello

        override fun doGet(request: HttpServletRequest, response: HttpServletResponse) {
            response.writer.write("hello")
        }
    }

    /** A server and the stack over it, chosen as Spring Boot chooses one: by what is on the class path. */
    enum class Server(val type: WebApplicationType, val hidden: List<String>) {
        NETTY_WEBFLUX(WebApplicationType.REACTIVE, listOf(JETTY, *TOMCAT)),
        TOMCAT_MVC(WebApplicationType.SERVLET, listOf(JETTY)),
        JETTY_MVC(WebApplicationType.SERVLET, listOf(*TOMCAT)),
        TOMCAT_WEBFLUX(WebApplicationType.REACTIVE, listOf(JETTY, NETTY_SERVER)),
        JETTY_WEBFLUX(WebApplicationType.REACTIVE, listOf(*TOMCAT, NETTY_SERVER)),
    }

    @ParameterizedTest
    @EnumSource(Server::class)
    fun `a mode the host's own post-processor adds last still derives the limit, on every server - IDN-43`(
        server: Server,
        output: CapturedOutput,
    ) {
        start(server, "${LateGatewayMode.SWITCH}=true").use { context ->
            val port = context.port()
            status(port, UNDER).assert().describedAs("headers under 8 KB on $server").isEqualTo(OK)
            status(port, OVER).assert().describedAs("headers over 8 KB on $server").isEqualTo(OK)
            context.serverLimit().assert().isEqualTo(DataSize.ofKilobytes(16))
        }
        output.all.assert().contains(
            "${IdentityHeaderLimit.SERVER_LIMIT_KEY} is 16384 bytes, derived from cosec.pipeline.identity.max-size " +
                "(8192 bytes)"
        )
    }

    @Test
    fun `an operator's limit stands, and the startup line names it as theirs - IDN-43`(output: CapturedOutput) {
        start(Server.NETTY_WEBFLUX, "${LateGatewayMode.SWITCH}=true", "${IdentityHeaderLimit.SERVER_LIMIT_KEY}=8KB")
            .use { context ->
                context.serverLimit().assert().isEqualTo(DataSize.ofKilobytes(8))
                val port = context.port()
                status(port, UNDER).assert().isEqualTo(OK)
                status(port, OVER).assert().describedAs("the operator's 8 KB refuses it").isNotEqualTo(OK)
            }
        output.all.assert().contains(
            "${IdentityHeaderLimit.SERVER_LIMIT_KEY} is 8192 bytes, below cosec.pipeline.identity.max-size " +
                "(8192 bytes) and 8192 bytes for the request's other headers (16384 bytes), as the operator set it"
        ).doesNotContain("derived from")
    }

    @Test
    fun `a host that reads no identity keeps the server's own limit, and says nothing of it - IDN-43`(
        output: CapturedOutput,
    ) {
        // The host's post-processor still puts it in gateway mode, but CoSec is off: nothing reads the identity.
        start(Server.NETTY_WEBFLUX, "${LateGatewayMode.SWITCH}=true", "cosec.enabled=false").use { context ->
            context.serverLimit().assert().isEqualTo(DataSize.ofKilobytes(8))
            status(context.port(), OVER).assert().isNotEqualTo(OK)
        }
        output.all.assert().doesNotContain("${IdentityHeaderLimit.SERVER_LIMIT_KEY} is")
    }

    private fun ConfigurableApplicationContext.serverLimit(): DataSize =
        getBean(ServerProperties::class.java).maxHttpRequestHeaderSize

    private fun ConfigurableApplicationContext.port(): Int =
        environment.getRequiredProperty("local.server.port").toInt()

    private fun start(server: Server, vararg settings: String): ConfigurableApplicationContext {
        val hidden = server.hidden + GATEWAY_GLOBAL_FILTER + OTHERS
        val gateway = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader)
            .filter { it.startsWith("org.springframework.cloud.gateway.") }
        val application = if (server.type == WebApplicationType.SERVLET) {
            ServletApplication::class.java
        } else {
            ReactiveApplication::class.java
        }
        // The guard that refuses a host believing the identity on a gateway looks the gateway up on the thread's class
        // loader too.
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = FilteredClassLoader(GATEWAY_GLOBAL_FILTER)
        return try {
            SpringApplicationBuilder(application)
                .web(server.type)
                .resourceLoader(DefaultResourceLoader(FilteredClassLoader(*hidden.toTypedArray())))
                .properties(
                    "server.address=127.0.0.1",
                    "server.port=0",
                    "cocache.backend=memory",
                    "cosec.jwt.enabled=false",
                    "cosec.pipeline.identity.only-gateway-can-reach=true",
                    "cosec.authorization.routes.location=",
                    "spring.main.banner-mode=off",
                    "spring.autoconfigure.exclude=" + gateway.joinToString(","),
                    *settings,
                )
                .run()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** The status of a `GET` with a forwarded identity and [padding] bytes of another header, on a new connection. */
    private fun status(port: Int, padding: Int): Int = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = SOCKET_MILLIS
        val request = "GET $PATH HTTP/1.1\r\nHost: localhost\r\nCoSec-Identity: ${IdentityHeader.forSubject("alice")}\r\n" +
            "X-Padding: ${"x".repeat(padding)}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(request.encodeToByteArray())
            flush()
        }
        val line = socket.getInputStream().bufferedReader().readLine()
        checkNotNull(line) { "the connection closed before a response" }.split(' ')[1].toInt()
    }

    private companion object {
        const val PATH = "/hello"
        const val OK = 200
        const val SOCKET_MILLIS = 10_000

        /** With the identity header and the request line, under Spring Boot's 8 KB. */
        const val UNDER = 7_000

        /** With the identity header, past Spring Boot's 8 KB and within the 16 KB derived for a host reading identities. */
        const val OVER = 9_000

        /** What a service behind a gateway does not bring: the control plane among them. */
        val OTHERS = listOf(
            "me.ahoo.cosec.control.api",
            "me.ahoo.cosec.cocache.control",
            "me.ahoo.cosec.kafka",
            "me.ahoo.cosec.social",
            "me.ahoo.cosec.openapi",
            "me.ahoo.cosec.cosid",
            "me.ahoo.cosec.opentelemetry",
            "me.ahoo.cosec.micrometer",
            "me.ahoo.cosec.ip2region",
        )
    }
}
