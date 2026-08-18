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

package me.ahoo.cosec.gateway

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.webflux.ReactiveRequest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.URI

class GatewayRouteRequestAttributesAppenderTest {
    private val appender = GatewayRouteRequestAttributesAppender()

    @Test
    fun appendRouteId() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1").build())
        exchange.attributes[GATEWAY_ROUTE_ATTR] = mockk<Route> {
            every { id } returns "orders"
        }
        val request = ReactiveRequest(
            delegate = exchange,
            path = "/orders/1",
            method = "GET",
            remoteIp = "127.0.0.1",
            origin = URI.create(""),
            referer = URI.create(""),
            requestId = "request-1",
        )

        val appended = appender.append(request)

        appended.attributes[AuditRequest.ROUTE_ID_ATTRIBUTE_KEY].assert().isEqualTo("orders")
    }

    @Test
    fun noRouteKeepsRequest() {
        val request = mockk<me.ahoo.cosec.api.context.request.Request>()

        appender.append(request).assert().isSameAs(request)
    }
}
