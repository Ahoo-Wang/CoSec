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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.XForwardedRemoteIpResolver
import me.ahoo.cosec.runtime.http.XForwardedRemoteIpResolver.Companion.X_FORWARDED_FOR
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress

class ReactiveXForwardedRemoteIpResolverTest {

    @Test
    fun extractXForwardedHeaderValues() {
        val xForwardedRemoteIpResolver = ReactiveXForwardedRemoteIpResolver()
        // The default trusts exactly one hop: the rightmost entry.
        xForwardedRemoteIpResolver.maxTrustedIndex.assert().isEqualTo(1)
        val serverRequest = MockServerHttpRequest.get("")
            .header(X_FORWARDED_FOR, "localhost")
        val serverWebExchange = MockServerWebExchange.from(serverRequest)
        val remoteIp = xForwardedRemoteIpResolver.extractXForwardedHeaderValues(serverWebExchange)
        remoteIp.assert().containsExactly("localhost")
    }

    /** PIP-17, DR1-C-8: a server that reports no peer (a Unix domain socket) gives an address that could not be read. */
    @Test
    fun aRequestWithoutAPeerReadsAsUnknown() {
        val noPeer = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        ReactiveRemoteIpResolver.resolve(noPeer).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
        ReactiveXForwardedRemoteIpResolver(0).resolve(noPeer).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
        ReactiveXForwardedRemoteIpResolver(1).resolve(noPeer).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
        val peer = MockServerWebExchange.from(
            MockServerHttpRequest.get("/orders").remoteAddress(InetSocketAddress("192.0.2.1", 50000))
        )
        ReactiveRemoteIpResolver.resolve(peer).assert().isEqualTo("192.0.2.1")
    }
}
