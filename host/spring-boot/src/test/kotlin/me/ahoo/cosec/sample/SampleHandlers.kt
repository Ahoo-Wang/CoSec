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

package me.ahoo.cosec.sample

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler

/** An application's own handlers, in a package of its own: the starter's package is not an application's. */
@RestController
class OrdersController {
    @GetMapping("/own")
    @Suppress("FunctionOnlyReturningConstant")
    fun own(): String = "own"
}

@Configuration(proxyBeanMethods = false)
class OwnHandlers {
    @Bean
    fun ordersController(): OrdersController = OrdersController()

    @Bean
    fun functionalRoutes(): RouterFunction<ServerResponse> =
        RouterFunctions.route().GET("/functional") { ServerResponse.ok().bodyValue("functional") }.build()
}

/** An application's own handler mapping: a WebSocket endpoint, as an application registers one (DR2-G-1). */
@Configuration(proxyBeanMethods = false)
class OwnHandlerMapping {
    @Bean
    fun socketMapping(): HandlerMapping = SimpleUrlHandlerMapping(mapOf("/socket" to WebSocketHandler { it.close() }), -1)
}
