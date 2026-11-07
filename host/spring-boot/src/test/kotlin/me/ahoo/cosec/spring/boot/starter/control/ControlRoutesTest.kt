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

package me.ahoo.cosec.spring.boot.starter.control

import io.mockk.mockk
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.web.servlet.function.ServerRequest as ServletServerRequest

/**
 * RC1-41: on either stack, a control API request no permitted principal stands behind is a 401 before any of its body
 * is read, so an anonymous caller cannot make the host buffer a body. The control plane is never asked either.
 */
class ControlRoutesTest {
    /** Fails on any call: a 401 asks the control plane nothing. */
    private val http = ControlHttp(mockk<ControlPlane>())
    private val properties = ControlProperties()

    @Test
    fun `on WebFlux a request without a permitted principal is a 401, its body never read`() {
        val read = AtomicBoolean()
        val routes = CoSecControlRoutesAutoConfiguration.WebFlux().cosecControlRoutes(http, properties)
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .uri(URI.create(PATH))
            .exchange(MockServerWebExchange.from(MockServerHttpRequest.post(PATH)))
            .body(Mono.fromCallable { read.set(true); BODY })
        val handler = routes.route(request).block()!!
        val response = handler.handle(request).block()!!
        response.statusCode().value().assert().isEqualTo(401)
        read.get().assert().isFalse()
    }

    @Test
    fun `on WebMvc a request without a permitted principal is a 401, its body never read`() {
        val read = AtomicBoolean()
        val servlet = object : MockHttpServletRequest("POST", PATH) {
            override fun getInputStream(): ServletInputStream {
                read.set(true)
                return object : ServletInputStream() {
                    override fun read(): Int = -1

                    override fun isFinished(): Boolean = true

                    override fun isReady(): Boolean = true

                    override fun setReadListener(listener: ReadListener?) = Unit
                }
            }
        }
        val routes = CoSecControlRoutesAutoConfiguration.WebMvc().cosecControlRoutes(http, properties)
        val request = ServletServerRequest.create(servlet, listOf(ByteArrayHttpMessageConverter()))
        val response = routes.route(request).get().handle(request)
        response.statusCode().value().assert().isEqualTo(401)
        read.get().assert().isFalse()
    }

    private companion object {
        const val PATH = "/cosec/control/v1/simulate"
        val BODY = """{"request":{}}""".encodeToByteArray()
    }
}
