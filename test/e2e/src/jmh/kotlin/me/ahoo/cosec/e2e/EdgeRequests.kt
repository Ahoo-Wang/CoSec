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

package me.ahoo.cosec.e2e

import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import jakarta.servlet.http.Cookie
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpCookie
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.server.reactive.AbstractServerHttpRequest
import org.springframework.http.server.reactive.SslInfo
import org.springframework.http.support.Netty4HeadersAdapter
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.adapter.DefaultServerWebExchange
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver
import org.springframework.web.server.session.DefaultWebSessionManager
import reactor.core.publisher.Flux
import java.net.InetSocketAddress
import java.net.URI

/**
 * The request of [EdgeRequestBenchmark], of the shape a gateway sees behind a load balancer: a browser's dozen headers,
 * a bearer token, two cookies (neither is CoSec's `authorization` cookie) and a three-hop `X-Forwarded-For`.
 */
internal object EdgeRequests {
    const val PATH = "/orders/op49/detail"
    const val QUERY = "page=2&size=20"

    /** The slice's token for the `role` request of [PipelineBenchmark]: `admin` of `app` permits the path. */
    const val TOKEN = "user@acme:viewer,admin|principal-a,principal-b#app"

    /** The connection's peer: the load balancer. */
    private val PEER = InetSocketAddress("10.0.0.1", 41234)

    val HEADERS: List<Pair<String, String>> = listOf(
        "Host" to "gateway.example.com",
        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9,zh-CN;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Authorization" to "Bearer $TOKEN",
        "Cookie" to "SESSION=5f0c8e2a-9d7b-4c1e-8a3f-2b6d1e0f9a47; theme=dark",
        "Referer" to "https://app.example.com/orders",
        "Origin" to "https://app.example.com",
        "X-Forwarded-For" to "203.0.113.7, 198.51.100.23, 10.0.0.2",
        "X-Forwarded-Proto" to "https",
        "X-Forwarded-Port" to "443",
        "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "Connection" to "keep-alive",
    )

    /** The headers a framework typically asks a request for after the filter (content negotiation, CORS, caching). */
    val FRAMEWORK_READS = arrayOf(
        "Accept",
        "Accept-Language",
        "Content-Type",
        "Origin",
        "If-None-Match",
        "If-Modified-Since",
        "X-Requested-With",
        "Access-Control-Request-Method",
        "User-Agent",
        "Accept-Encoding",
    )

    private val URI_OF_REQUEST: URI = URI.create("http://gateway.example.com$PATH?$QUERY")
    private val NETTY_HEADERS = DefaultHttpHeaders().apply { HEADERS.forEach { (name, value) -> add(name, value) } }
    private val SESSIONS = DefaultWebSessionManager()
    private val CODECS = ServerCodecConfigurer.create()
    private val LOCALES = AcceptHeaderLocaleContextResolver()

    /**
     * A new exchange per request, as `HttpWebHandlerAdapter` makes one. The Netty headers are shared: nothing writes
     * them (Spring's request builder copies them first), and a server hands each request its own.
     */
    fun exchange(): ServerWebExchange =
        DefaultServerWebExchange(NettyShapedRequest(URI_OF_REQUEST, NETTY_HEADERS, PEER), MockServerHttpResponse(), SESSIONS, CODECS, LOCALES)

    /** The same request as a servlet container hands it to a filter. */
    fun servletRequest(): MockHttpServletRequest = MockHttpServletRequest("GET", PATH).apply {
        queryString = QUERY
        remoteAddr = PEER.address.hostAddress
        remotePort = PEER.port
        HEADERS.forEach { (name, value) -> addHeader(name, value) }
        setCookies(Cookie("SESSION", "5f0c8e2a-9d7b-4c1e-8a3f-2b6d1e0f9a47"), Cookie("theme", "dark"))
    }

    fun servletResponse(): MockHttpServletResponse = MockHttpServletResponse()

    /**
     * A request as Reactor Netty hands it to Spring (`ReactorServerHttpRequest`): Netty's headers behind Spring's
     * adapter, read-only, and the cookies decoded from the `Cookie` headers on first read, with Netty's decoder.
     * `MockServerHttpRequest` would hold neither: its headers are a Spring map and its cookies come pre-parsed.
     */
    private class NettyShapedRequest(
        uri: URI,
        private val netty: io.netty.handler.codec.http.HttpHeaders,
        private val peer: InetSocketAddress,
    ) : AbstractServerHttpRequest(HttpMethod.GET, uri, "", HttpHeaders(Netty4HeadersAdapter(netty))) {
        override fun initCookies(): MultiValueMap<String, HttpCookie> {
            val cookies = LinkedMultiValueMap<String, HttpCookie>()
            for (header in netty.getAll(HttpHeaderNames.COOKIE)) {
                for (cookie in ServerCookieDecoder.LAX.decodeAll(header)) {
                    cookies.add(cookie.name(), HttpCookie(cookie.name(), cookie.value()))
                }
            }
            return cookies
        }

        override fun initSslInfo(): SslInfo? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getNativeRequest(): T = netty as T

        override fun getRemoteAddress(): InetSocketAddress = peer

        override fun getBody(): Flux<DataBuffer> = Flux.empty()
    }
}
