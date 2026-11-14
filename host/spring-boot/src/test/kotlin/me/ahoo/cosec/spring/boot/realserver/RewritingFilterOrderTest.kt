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
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.net.Socket

/**
 * Round 2 M1: CoSec authorizes the request that is executed. Spring Boot's hidden-method filter
 * (`spring.mvc.hiddenmethod.filter.enabled`, `spring.webflux.hiddenmethod.filter.enabled`) turns `POST` with
 * `_method=DELETE` into a DELETE; on both stacks it runs before CoSec, so the DELETE is what CoSec decides. Real
 * Spring Boot applications, with Boot's own filter registration and ordering, on Tomcat and Reactor Netty.
 */
class RewritingFilterOrderTest {
    @RestController
    class Orders {
        @DeleteMapping("/orders/{id}")
        fun delete(@PathVariable id: String): String = "$SERVED DELETE $id"

        @PostMapping("/orders/{id}")
        fun post(@PathVariable id: String): String = "$SERVED POST $id"
    }

    /** The starter's servlet filter, over the suite's pipeline: clerks may do anything to orders but DELETE them. */
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
        fun pipeline(): Pipeline = FIXTURE.pipeline

        @Bean
        fun orders() = Orders()
    }

    /** WebFlux: the WebFlux filter at its order, as the starter registers it on a WebFlux host. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        NettyReactiveWebServerAutoConfiguration::class,
        HttpHandlerAutoConfiguration::class,
        WebFluxAutoConfiguration::class,
    )
    class Flux {
        @Bean
        fun cosecPipelineWebFilter() = PipelineWebFilter(FIXTURE.pipeline)

        @Bean
        fun orders() = Orders()
    }

    private fun post(port: Int): RawResponse = Socket("127.0.0.1", port).use { socket ->
        val body = "_method=DELETE"
        val request = "POST /orders/1 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer alice@acme:clerk\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n$body"
        socket.getOutputStream().write(request.toByteArray())
        RawResponse.parse(socket.getInputStream().readBytes())
    }

    private fun answer(source: Class<*>, type: WebApplicationType, property: String): RawResponse {
        val context = SpringApplicationBuilder(source).web(type)
            .properties("server.port=0", "spring.main.banner-mode=off", "$property=true")
            // Spring Cloud Gateway, on this test classpath, forces the reactive filter off ahead of every other source; a
            // plain WebFlux application has no such source.
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> {
                    it.environment.propertySources.remove(GATEWAY_PROPERTIES)
                },
            )
            .run()
        return context.use { post((it as WebServerApplicationContext).webServer!!.port) }
    }

    @Test
    fun `a hidden DELETE is authorized as the DELETE it is on Spring MVC`() {
        val answer = answer(Mvc::class.java, WebApplicationType.SERVLET, "spring.mvc.hiddenmethod.filter.enabled")
        answer.status.assert().describedAs(answer.toString()).isEqualTo(403)
    }

    @Test
    fun `a hidden DELETE is authorized as the DELETE it is on WebFlux`() {
        val answer = answer(Flux::class.java, WebApplicationType.REACTIVE, "spring.webflux.hiddenmethod.filter.enabled")
        answer.status.assert().describedAs(answer.toString()).isEqualTo(403)
    }

    private companion object {
        val FIXTURE = ParityFixture()

        /** The property source of `GatewayEnvironmentPostProcessor`. */
        const val GATEWAY_PROPERTIES = "gateway-properties"
    }
}
