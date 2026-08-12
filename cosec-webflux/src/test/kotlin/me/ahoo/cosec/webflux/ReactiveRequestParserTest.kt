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

import me.ahoo.cosec.context.request.XForwardedRemoteIpResolver.Companion.X_FORWARDED_FOR
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetAddress
import java.net.InetSocketAddress

internal class ReactiveRequestParserTest {

    @Test
    fun parse() {
        val requestParser = ReactiveRequestParser(ReactiveRemoteIpResolver)
        val peerAddress = InetAddress.getByAddress("peer.example", byteArrayOf(127, 0, 0, 1))
        val serverRequest = MockServerHttpRequest.get("/path")
            .remoteAddress(InetSocketAddress(peerAddress, 8080))
            .header(X_FORWARDED_FOR, "localhost")
        val serverWebExchange = MockServerWebExchange.from(serverRequest)
        val request = requestParser.parse(serverWebExchange)
        request.path.assert().isEqualTo("/path")
        request.method.assert().isEqualTo("GET")
        request.remoteIp.assert().isEqualTo("127.0.0.1")
    }
}
