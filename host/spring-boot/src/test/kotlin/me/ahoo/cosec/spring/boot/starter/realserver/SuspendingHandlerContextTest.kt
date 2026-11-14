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

import kotlinx.coroutines.CompletableDeferred
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.servlet.SecurityContextHolder
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.RuntimeAuthorizationProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Hooks
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 2 M4: a `suspend` handler on Spring MVC, as Spring Boot configures it, resumed on the thread of another
 * request: alice's handler waits for what bob's handler completes. Spring runs `suspend` handlers on
 * `Dispatchers.Unconfined`, so alice's handler goes on on bob's thread, inside bob's filter. The `SecurityContext`
 * parameter ([me.ahoo.cosec.servlet.SecurityContextArgumentResolver]) is read from alice's request; the thread-bound
 * holder answers as the thread's context propagation leaves it.
 */
class SuspendingHandlerContextTest {
    @RestController
    class Waiting {
        val entered = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()

        @GetMapping("/orders/a")
        suspend fun first(context: SecurityContext): String {
            entered.countDown()
            gate.await()
            return "parameter=${context.principal.id} holder=${SecurityContextHolder.context?.principal?.id}"
        }

        @GetMapping("/orders/b")
        suspend fun second(): String {
            gate.complete(Unit)
            return "done"
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        TomcatServletWebServerAutoConfiguration::class,
        DispatcherServletAutoConfiguration::class,
        WebMvcAutoConfiguration::class,
        CoSecPipelineWebAutoConfiguration::class,
    )
    @EnableConfigurationProperties(RuntimeAuthorizationProperties::class)
    class Mvc {
        @Bean
        fun pipeline(): Pipeline = ParityFixture().pipeline

        @Bean
        fun waiting() = Waiting()
    }

    private fun get(port: Int, path: String, who: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = TIMEOUT_MILLIS
        socket.getOutputStream().write(
            (
                "GET $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $who@acme:clerk\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        RawResponse.parse(socket.getInputStream().readBytes()).body
    }

    /**
     * What alice's handler read after bob's request resumed it; with [automatic], under Reactor's automatic context
     * propagation, which is what Spring Boot's `spring.reactor.context-propagation=auto` turns on.
     */
    private fun resumed(automatic: Boolean = false): String {
        if (automatic) {
            Hooks.enableAutomaticContextPropagation()
        }
        val context = SpringApplicationBuilder(Mvc::class.java).web(WebApplicationType.SERVLET)
            .properties("server.port=0", "spring.main.banner-mode=off")
            .run()
        try {
            val port = (context as WebServerApplicationContext).webServer!!.port
            val waiting = context.getBean(Waiting::class.java)
            val alice = CompletableFuture.supplyAsync { get(port, "/orders/a", "alice") }
            waiting.entered.await(TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS).assert().isTrue()
            get(port, "/orders/b", "bob").assert().isEqualTo("done")
            return alice.get(TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            context.close()
            Hooks.disableAutomaticContextPropagation()
        }
    }

    /** The parameter is the request's whatever thread the handler resumes on: the way to read it in a handler. */
    @Test
    fun `the SecurityContext parameter is the handler's own request's`() {
        resumed().assert().startsWith("parameter=alice ")
    }

    /**
     * By default Spring restores no thread-local when a `suspend` handler resumes: the holder answers bob's context in
     * alice's handler. Asserted as it is, so a change in Spring's default shows here; the docs say to read the parameter.
     */
    @Test
    fun `by default the holder in a resumed suspend handler is the thread's request's`() {
        resumed().assert().isEqualTo("parameter=alice holder=bob")
    }

    /** With Spring's automatic context propagation, the holder is restored for the handler too. */
    @Test
    fun `with automatic context propagation the holder is the handler's own request's`() {
        resumed(automatic = true).assert().isEqualTo("parameter=alice holder=alice")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
    }
}
