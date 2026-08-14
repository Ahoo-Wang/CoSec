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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.context.request.InvalidRequestPathException
import me.ahoo.cosec.context.request.XForwardedRemoteIpResolver.Companion.X_FORWARDED_FOR
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress
import java.net.URI

internal class ReactiveRequestParserTest {

    @Test
    fun parse() {
        val requestParser = ReactiveRequestParser(ReactiveRemoteIpResolver)
        val serverRequest = MockServerHttpRequest.get("/path")
            .remoteAddress(InetSocketAddress.createUnresolved("localhost", 8080))
            .header(X_FORWARDED_FOR, "localhost")
        val serverWebExchange = MockServerWebExchange.from(serverRequest)
        val request = requestParser.parse(serverWebExchange)
        assertThat(request.path, `is`("/path"))
        assertThat(request.method, `is`("GET"))
    }

    @Test
    fun parseWhenTraversalPath() {
        // MockServerHttpRequest.get(String) treats the string as a URI template and re-encodes
        // '%' to '%25'; a pre-encoded URI mirrors what a real server delivers to the parser.
        val serverRequest = MockServerHttpRequest.method(HttpMethod.GET, URI.create("/public/%2e%2e/admin")).build()
        val exchange = MockServerWebExchange.builder(serverRequest).build()
        val parser = ReactiveRequestParser(ReactiveRemoteIpResolver)
        assertThrows<InvalidRequestPathException> { parser.parse(exchange) }
    }
}
