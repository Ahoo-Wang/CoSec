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

import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.CompletableDeferred
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.webmvc.CoSecSecurityContextHolder
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
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
 * parameter ([me.ahoo.cosec.webmvc.SecurityContextArgumentResolver]) is read from alice's request. The holder is
 * unbound in a `suspend` handler unless Spring restores it ([me.ahoo.cosec.webmvc.SuspendingHandlerHolderInterceptor]),
 * so it fails loudly, before and after the suspension, instead of naming bob.
 */
class SuspendingHandlerContextTest {
    @RestController
    class Waiting {
        /** Alice's request has left its thread, so her handler is suspended at [gate] ([Mvc.suspension]). */
        val suspended = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()

        private fun holder(): String = runCatching { CoSecSecurityContextHolder.requireSecurityContext().principal.id }
            .getOrElse { if (it.message.orEmpty().startsWith(UNBOUND)) "unbound" else throw it }

        /** Alice's: reads the holder before it suspends and after it is resumed. */
        @GetMapping("/orders/a")
        suspend fun first(context: SecurityContext): String {
            val before = holder()
            gate.await()
            return "parameter=${context.principal.id} before=$before after=${holder()}"
        }

        /** Bob's, suspending: resumes alice's handler on its own thread. */
        @GetMapping("/orders/b")
        suspend fun second(): String {
            gate.complete(Unit)
            return "done"
        }

        /** Bob's, blocking: resumes alice's handler on a thread whose holder is bob's. */
        @GetMapping("/orders/c")
        fun blocking(): String {
            gate.complete(Unit)
            return holder()
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

        /**
         * Counts [Waiting.suspended] down once the thread of alice's request returns from the filter chain with the request
         * asynchronous: her handler is then suspended at its gate, not about to reach it. Bob's request is sent only after
         * that, so it resumes her handler on its own thread, never before she suspends.
         */
        @Bean
        fun suspension(waiting: Waiting): FilterRegistrationBean<Filter> = FilterRegistrationBean(
            Filter { request, response, chain ->
                chain.doFilter(request, response)
                if ((request as HttpServletRequest).requestURI == "/orders/a" && request.isAsyncStarted) {
                    waiting.suspended.countDown()
                }
            }
        ).apply { order = Ordered.HIGHEST_PRECEDENCE }
    }

    private fun get(port: Int, path: String, who: String): String = Socket(LOOPBACK, port).use { socket ->
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
     * What alice's handler read after bob's request to [resumer] resumed it, and what that request answered; with
     * [automatic], under Reactor's automatic context propagation, which `spring.reactor.context-propagation=auto` turns on.
     */
    private fun resumed(resumer: String = "/orders/b", automatic: Boolean = false): Pair<String, String> {
        if (automatic) {
            Hooks.enableAutomaticContextPropagation()
        }
        val context = SpringApplicationBuilder(Mvc::class.java).web(WebApplicationType.SERVLET)
            .properties("server.address=$LOOPBACK", "server.port=0", "spring.main.banner-mode=off")
            .run()
        try {
            val port = (context as WebServerApplicationContext).webServer!!.port
            val waiting = context.getBean(Waiting::class.java)
            val alice = CompletableFuture.supplyAsync { get(port, "/orders/a", "alice") }
            val suspended = waiting.suspended.await(TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            val answered = if (alice.isDone) alice.handle { body, error -> body ?: error.toString() }.join() else "nothing yet"
            val why = "alice's handler did not suspend within the bound; her request answered: $answered"
            suspended.assert().describedAs(why).isTrue()
            val bob = get(port, resumer, "bob")
            return alice.get(TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS) to bob
        } finally {
            context.close()
            Hooks.disableAutomaticContextPropagation()
        }
    }

    /** The parameter is the request's whatever thread the handler resumes on: the way to read it in a handler. */
    @Test
    fun `the SecurityContext parameter is the handler's own request's`() {
        resumed().first.assert().startsWith("parameter=alice ")
    }

    /** Without propagation the holder is unbound in a suspend handler: it fails before and after the suspension. */
    @Test
    fun `without context propagation the holder fails loudly in a suspend handler`() {
        resumed().first.assert().isEqualTo("parameter=alice before=unbound after=unbound")
    }

    /** With Spring's automatic context propagation, the holder stays bound and is restored on resumption. */
    @Test
    fun `with automatic context propagation the holder is the handler's own request's`() {
        resumed(automatic = true).first.assert().isEqualTo("parameter=alice before=alice after=alice")
    }

    /** A blocking handler keeps the holder, as before. */
    @Test
    fun `a blocking handler reads the holder`() {
        resumed("/orders/c").second.assert().isEqualTo("bob")
    }

    /**
     * The one case left, documented in the holder's KDoc and `web.md`: resumed on the thread of a blocking handler's
     * request, a suspend handler finds that request's context there. It already failed before the suspension.
     */
    @Test
    fun `resumed by a blocking handler's request the holder is that request's`() {
        resumed("/orders/c").first.assert().isEqualTo("parameter=alice before=unbound after=bob")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000

        /**
         * The server binds this address, not every address, and the requests go to it: bound to every address on a port
         * the system chose, a server shares that port with any process bound to one address on it, which then takes the
         * connections to that address. On a machine running other builds alice's handler once never ran within the bound.
         */
        const val LOOPBACK = "127.0.0.1"

        /** How the holder's message starts: it names what to read instead. */
        const val UNBOUND = "No SecurityContext is bound to this thread"
    }
}
