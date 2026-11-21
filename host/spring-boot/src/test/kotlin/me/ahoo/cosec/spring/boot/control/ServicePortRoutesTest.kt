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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseCookie
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.InetSocketAddress

/**
 * `CTL-58`, R3-5: on the service port the control routes are asked directly, and "no route matched" is decided on the
 * route lookup. A route that was served is never turned into a `404`, whatever its write left behind, and a path no
 * route answers is one.
 */
class ServicePortRoutesTest {
    /** A response whose write commits nothing and completes empty: what a served route may leave behind. */
    private class Uncommitted : ServerResponse {
        var written = 0

        override fun statusCode(): HttpStatusCode = HttpStatusCode.valueOf(OK)

        override fun headers(): HttpHeaders = HttpHeaders()

        override fun cookies(): MultiValueMap<String, ResponseCookie> = LinkedMultiValueMap()

        override fun writeTo(exchange: ServerWebExchange, context: ServerResponse.Context): Mono<Void> {
            written++
            return Mono.empty()
        }
    }

    private fun exchange(path: String, port: Int = SERVICE_PORT): MockServerWebExchange = MockServerWebExchange.from(
        MockServerHttpRequest.get(path).localAddress(InetSocketAddress(port)).build(),
    )

    @Test
    fun `a route that was served is never turned into a 404, whatever its write left behind`() {
        val response = Uncommitted()
        val routes = RouterFunctions.route().GET("/served") { Mono.just(response) }.build()
        val exchange = exchange("/served")
        ServicePortRoutes({
            SERVICE_PORT
        }, routes).filter(exchange) { Mono.error(IllegalStateException("not the chain's")) }.block()
        response.written.assert().isEqualTo(1)
        exchange.response.statusCode.assert().describedAs("nothing touched the status of a served route").isNull()
        exchange.response.isCommitted.assert().isFalse()
    }

    @Test
    fun `a path no control route answers is a 404 on the service port, and a request on another port goes on`() {
        val routes = RouterFunctions.route().GET("/served") { Mono.just(Uncommitted()) }.build()
        val unrouted = exchange("/elsewhere")
        ServicePortRoutes({
            SERVICE_PORT
        }, routes).filter(unrouted) { Mono.error(IllegalStateException("not the chain's")) }.block()
        unrouted.response.statusCode.assert().isEqualTo(HttpStatusCode.valueOf(NOT_FOUND))

        var passed = false
        ServicePortRoutes({ SERVICE_PORT }, routes).filter(exchange("/served", port = OTHER_PORT)) {
            passed = true
            Mono.empty()
        }.block()
        passed.assert().describedAs("a request on another port is the chain's").isTrue()
    }

    private companion object {
        const val SERVICE_PORT = 8082
        const val OTHER_PORT = 8080
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
