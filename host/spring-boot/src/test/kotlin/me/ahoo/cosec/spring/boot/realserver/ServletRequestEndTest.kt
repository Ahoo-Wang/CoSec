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

import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import me.ahoo.cosec.webmvc.checkBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jetty.autoconfigure.servlet.JettyServletWebServerAutoConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * `PIP-26` on the servlet containers the starter runs on (Tomcat and Jetty, as Spring Boot builds them), through Spring
 * MVC: a request the handler made async is in progress until the container completes it, not until the filter
 * returns, also when it was completed before the original dispatch returned (an already completed `DeferredResult` or
 * `CompletableFuture`), so application code on the ASYNC dispatch (a `ResponseBodyAdvice` here) is answered; once
 * the response is written, a check of the same context is denied. The ERROR dispatch, which the filter does not take,
 * finds the context and every check of it denied: the request ended when the REQUEST dispatch returned.
 */
class ServletRequestEndTest {
    @RestController
    class Handlers(private val checker: AuthorizationChecker) {
        val kept = ConcurrentHashMap<String, SecurityContext>()

        /** Suspends, and checks after it is resumed, before it returns. */
        @GetMapping("/orders/suspending")
        suspend fun suspending(context: SecurityContext): String {
            kept["suspending"] = context
            delay(SUSPENSION_MILLIS)
            return "resumed=${checker.check(context, ACTION, RESOURCE)}"
        }

        /** Completed before the handler returns: Spring dispatches ASYNC before the REQUEST dispatch returns. */
        @GetMapping("/orders/deferred")
        fun deferred(context: SecurityContext): DeferredResult<String> {
            kept["deferred"] = context
            return DeferredResult<String>().apply { setResult("deferred") }
        }

        @GetMapping("/orders/future")
        fun future(context: SecurityContext): CompletableFuture<String> {
            kept["future"] = context
            return CompletableFuture.completedFuture("future")
        }

        /** Fails: the container then renders the error page on an ERROR dispatch. */
        @GetMapping("/orders/failing")
        fun failing(context: SecurityContext, response: HttpServletResponse) {
            kept["failing"] = context
            response.sendError(IM_A_TEAPOT)
        }
    }

    /** Application code on the ASYNC dispatch: checks as the request's principal while the body is written. */
    @ControllerAdvice
    class CheckingAdvice(private val checker: AuthorizationChecker) : ResponseBodyAdvice<Any> {
        override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) = true

        override fun beforeBodyWrite(
            body: Any?,
            returnType: MethodParameter,
            selectedContentType: MediaType,
            selectedConverterType: Class<out HttpMessageConverter<*>>,
            request: ServerHttpRequest,
            response: ServerHttpResponse,
        ): Any? {
            val servlet = (request as ServletServerHttpRequest).servletRequest
            if (body !is String || servlet.dispatcherType != DispatcherType.ASYNC) {
                return body
            }
            val context = checkNotNull(servlet.securityContextOrNull())
            return "$body advice=${checker.checkBlocking(context, ACTION, RESOURCE)} on=${servlet.dispatcherType}" +
                " principal=${servlet.userPrincipal?.name}"
        }
    }

    /** The error page: reads the context and checks with it, on the ERROR dispatch. */
    @RestController
    class Errors(private val checker: AuthorizationChecker) : ErrorController {
        @RequestMapping("/error")
        fun error(request: HttpServletRequest): String {
            val context = request.securityContextOrNull()
            val check = context?.let { checker.checkBlocking(it, ACTION, RESOURCE) }
            return "error context=${context?.principal?.id} check=$check on=${request.dispatcherType}" +
                " principal=${request.userPrincipal?.name}"
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        DispatcherServletAutoConfiguration::class,
        WebMvcAutoConfiguration::class,
        ErrorMvcAutoConfiguration::class,
        CoSecPipelineWebAutoConfiguration::class,
    )
    @EnableConfigurationProperties(RuntimeAuthorizationProperties::class)
    class Mvc {
        @Bean
        fun pipeline(): Pipeline = ParityFixture().pipeline

        @Bean
        fun checker(pipeline: Pipeline) = AuthorizationChecker(pipeline)

        @Bean
        fun handlers(checker: AuthorizationChecker) = Handlers(checker)

        @Bean
        fun checkingAdvice(checker: AuthorizationChecker) = CheckingAdvice(checker)

        @Bean
        fun errors(checker: AuthorizationChecker) = Errors(checker)
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration::class)
    class OnTomcat

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(JettyServletWebServerAutoConfiguration::class)
    class OnJetty

    private fun start(container: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(
            Mvc::class.java,
            if (container == "tomcat") OnTomcat::class.java else OnJetty::class.java
        )
            .web(WebApplicationType.SERVLET)
            .properties("server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off")
            .run()

    private fun get(context: ConfigurableApplicationContext, path: String): RawResponse {
        val port = (context as WebServerApplicationContext).webServer!!.port
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            socket.getOutputStream().write(
                (
                    "GET $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer alice@acme:clerk\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            RawResponse.parse(socket.getInputStream().readBytes())
        }
    }

    /**
     * Whether a check of [kept] is denied once its request has completed. The container ends an async request when it
     * completes it, which may be just after the client has read the last byte: waited for, briefly.
     */
    private fun deniedOnceCompleted(checker: AuthorizationChecker, kept: SecurityContext): Boolean {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!checker.checkBlocking(kept, ACTION, RESOURCE)) {
                return true
            }
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    @ParameterizedTest
    @ValueSource(strings = ["tomcat", "jetty"])
    fun `a suspend handler checks after it resumed, and the context is denied once the response is written`(
        container: String,
    ) {
        start(container).use { context ->
            val checker = context.getBean(AuthorizationChecker::class.java)
            val body = get(context, "/orders/suspending").body
            body.assert().contains("resumed=true")
            // PIP-28: the ASYNC dispatch is the request the handler started async on, with CoSec's principal.
            body.assert().contains("advice=true on=ASYNC principal=alice")
            deniedOnceCompleted(checker, context.getBean(Handlers::class.java).kept.getValue("suspending"))
                .assert().isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["tomcat", "jetty"])
    fun `an async result completed before the handler returned is answered on the ASYNC dispatch, then denied`(
        container: String,
    ) {
        start(container).use { context ->
            val checker = context.getBean(AuthorizationChecker::class.java)
            val kept = context.getBean(Handlers::class.java).kept
            for (path in listOf("deferred", "future")) {
                get(context, "/orders/$path").body.assert().contains("$path advice=true on=ASYNC principal=alice")
                deniedOnceCompleted(checker, kept.getValue(path)).assert().isTrue()
            }
        }
    }

    /**
     * The filter takes the REQUEST dispatch only: the error page reads the context, and every check of it is denied.
     * The container renders it on its own request, not the one the filter handed on, so it carries none of CoSec's
     * principal (`PIP-28`): the request has ended.
     */
    @ParameterizedTest
    @ValueSource(strings = ["tomcat", "jetty"])
    fun `the error page finds the context and a check of it is denied`(container: String) {
        start(container).use { context ->
            get(context, "/orders/failing").body.assert()
                .contains("error context=alice check=false on=ERROR principal=null")
        }
    }

    private companion object {
        const val ACTION = "http:GET"
        const val RESOURCE = "/orders/1"
        const val SUSPENSION_MILLIS = 50L
        const val IM_A_TEAPOT = 418
        const val TIMEOUT_MILLIS = 10_000
        const val POLL_MILLIS = 10L
    }
}
