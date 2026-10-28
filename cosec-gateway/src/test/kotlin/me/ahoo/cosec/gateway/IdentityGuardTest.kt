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

import me.ahoo.cosec.identity.IdentityCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.factory.AbstractNameValueGatewayFilterFactory.NameValueConfig
import org.springframework.cloud.gateway.filter.factory.AddRequestHeaderGatewayFilterFactory
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Duration

/** `PIP-10` at the gateway's edges: a client's identity is stripped first, and nothing but the pipeline's goes out. */
class IdentityGuardTest {
    @Test
    fun `the strip filter removes a client's identity before anything reads the request`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders").header(IdentityCodec.HEADER, "forged"))
        var seen: HttpHeaders? = null
        IdentityStripFilter().filter(exchange, GatewayFilterChain { next ->
            seen = next.request.headers
            Mono.empty()
        }).block(Duration.ofSeconds(5))
        seen!!.containsHeader(IdentityCodec.HEADER).assert().isFalse()
        IdentityStripFilter().order.assert().isLessThan(PipelineGatewayFilter.AUTHORIZATION_GATEWAY_FILTER_ORDER)
    }

    /** What the routing filter sends after a route's `AddRequestHeader=CoSec-Identity, forged` ran. */
    private fun lastHop(forwarded: String?): HttpHeaders {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        forwarded?.let { exchange.attributes[IdentityHeadersFilter.FORWARDED_ATTRIBUTE] = it }
        val config = NameValueConfig().setName(IdentityCodec.HEADER).setValue("forged")
        var routed: ServerWebExchange? = null
        AddRequestHeaderGatewayFilterFactory().apply(config).filter(exchange) { next ->
            routed = next
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        routed!!.request.headers.getFirst(IdentityCodec.HEADER).assert().isEqualTo("forged")
        return HttpHeadersFilter.filterRequest(listOf(IdentityHeadersFilter()), routed!!)
    }

    @Test
    fun `a route filter cannot forward an identity of its own`() {
        lastHop(forwarded = "verified").getFirst(IdentityCodec.HEADER).assert().isEqualTo("verified")
        lastHop(forwarded = "verified")[IdentityCodec.HEADER].assert().isEqualTo(listOf("verified"))
        // Anonymous, or a route that opted out: the pipeline forwarded nothing, so nothing goes out.
        lastHop(forwarded = null).containsHeader(IdentityCodec.HEADER).assert().isFalse()
    }
}
