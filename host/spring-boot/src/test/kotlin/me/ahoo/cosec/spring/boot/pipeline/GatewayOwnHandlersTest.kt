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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.sample.OwnHandlerMapping
import me.ahoo.cosec.sample.OwnHandlers
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

/**
 * UB-10, `STA-13`: on a Spring Cloud Gateway host the application's own controllers, functional routes and handler
 * mappings are answered outside any gateway route, so a global filter (CoSec's) never sees them; startup fails,
 * naming them, unless `cosec.gateway.unprotected-handlers` lists each.
 */
class GatewayOwnHandlersTest {
    class Counting : GlobalFilter {
        val seen = AtomicInteger()

        override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
            seen.incrementAndGet()
            exchange.response.statusCode = org.springframework.http.HttpStatus.OK
            return exchange.response.setComplete()
        }
    }

    @Test
    fun `a controller and a functional route are not seen by a gateway global filter, a route is`() {
        val filter = Counting()
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SslAutoConfiguration::class.java,
                    WebFluxAutoConfiguration::class.java,
                    GatewayAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(OwnHandlers::class.java)
            .withBean(Counting::class.java, { filter })
            .withPropertyValues(
                "spring.cloud.gateway.server.webflux.routes[0].id=routed",
                "spring.cloud.gateway.server.webflux.routes[0].uri=http://localhost:1",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/routed/**",
            )
            .run { context ->
                context.startupFailure.assert().isNull()
                val client = WebTestClient.bindToApplicationContext(context).build()
                client.get().uri("/routed/x").exchange().expectStatus().isOk
                filter.seen.get().assert().isEqualTo(1)
                client.get().uri("/own").exchange().expectStatus().isOk.expectBody(String::class.java)
                    .isEqualTo("own")
                client.get().uri("/functional").exchange().expectStatus().isOk.expectBody(String::class.java)
                    .isEqualTo("functional")
                // Neither answer passed through the filter: nothing decided them.
                filter.seen.get().assert().isEqualTo(1)
                GatewayOwnHandlers.find(context).assert().containsExactly("functionalRoutes", "ordersController")
            }
    }

    @Test
    fun `the warning names the handlers, and there is none without them`() {
        AnnotationConfigApplicationContext(OwnHandlers::class.java).use { context ->
            val message = GatewayOwnHandlers.message(GatewayOwnHandlers.find(context))
            message.assert().isNotNull()
            message!!.assert().contains("ordersController").contains("functionalRoutes").contains("gateway routes only")
        }
        AnnotationConfigApplicationContext().use { context ->
            context.refresh()
            GatewayOwnHandlers.message(GatewayOwnHandlers.find(context)).assert().isNull()
        }
    }

    @Test
    fun `a handler of the application that is not listed fails startup, naming it, the property and the page`() {
        AnnotationConfigApplicationContext(OwnHandlers::class.java).use { context ->
            val refusal = assertThrows<IllegalStateException> { GatewayOwnHandlers.require(context, emptySet()) }
            refusal.message.assert().contains("ordersController").contains("functionalRoutes")
                .contains(GatewayProperties.UNPROTECTED_HANDLERS_KEY).contains("STA-13")
                .contains(GatewayOwnHandlers.DOCUMENTATION)
            // Listing one leaves the other refused.
            assertThrows<IllegalStateException> { GatewayOwnHandlers.require(context, setOf("ordersController")) }
                .message.assert().contains("functionalRoutes").doesNotContain("ordersController,")
        }
    }

    /** DR2-G-1: a handler mapping of the application's own, a WebSocket endpoint's, is one of its handlers too. */
    @Test
    fun `a handler mapping of the application that is not listed fails startup, naming it`() {
        AnnotationConfigApplicationContext(OwnHandlerMapping::class.java).use { context ->
            GatewayOwnHandlers.find(context).assert().containsExactly("socketMapping")
            assertThrows<IllegalStateException> { GatewayOwnHandlers.require(context, emptySet()) }
                .message.assert().contains("socketMapping").contains("STA-13")
            assertDoesNotThrow { GatewayOwnHandlers.require(context, setOf("socketMapping")) }
        }
    }

    @Test
    fun `every handler listed starts, and a name that matches no handler is ignored`() {
        AnnotationConfigApplicationContext(OwnHandlers::class.java).use { context ->
            assertDoesNotThrow {
                GatewayOwnHandlers.require(context, setOf("ordersController", " functionalRoutes ", "noSuchHandler"))
            }
        }
        AnnotationConfigApplicationContext().use { context ->
            context.refresh()
            assertDoesNotThrow { GatewayOwnHandlers.require(context, emptySet()) }
        }
    }

    @Test
    fun `a pattern is never accepted`() {
        AnnotationConfigApplicationContext(OwnHandlers::class.java).use { context ->
            for (pattern in listOf("*", "orders*")) {
                assertThrows<IllegalStateException> {
                    GatewayOwnHandlers.require(context, setOf(pattern, "ordersController", "functionalRoutes"))
                }.message.assert().contains("never a pattern").contains(pattern)
            }
        }
    }
}
