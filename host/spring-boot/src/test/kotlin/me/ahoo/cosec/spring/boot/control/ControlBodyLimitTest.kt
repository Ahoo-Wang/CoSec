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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.control

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.http.HttpControlService
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spring.boot.pipeline.ApplicationPort
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import me.ahoo.cosec.webmvc.ServletRequests.setSecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Flux
import java.net.URI
import java.time.Instant
import org.springframework.web.servlet.function.ServerRequest as ServletServerRequest

/**
 * `CTL-41`: on either stack, the control routes read at most the body limit of a permitted request's body (the 8 MB
 * document cap and 2 MB, [ControlDerived.MAX_BODY_BYTES]), and answer a larger one 400 without asking the control plane.
 */
class ControlBodyLimitTest {
    private val plane = mockk<ControlPlane>().also { plane ->
        every { plane.work } returns ControlDerived.workBudget()
        coEvery { plane.read(any(), any(), any()) } returns
            PolicyDocument(DocumentKind.TENANT, "acme", 1, Instant.parse("2026-11-01T00:00:00Z"), emptyList(), emptyList())
    }
    private val http = ControlHttp(plane)
    private val properties = ControlProperties()
    private val security = permittedSecurity(PATH)

    private fun onWebFlux(size: Int): Pair<Int, String> {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(PATH))
        exchange.setSecurityContext(security.securityContext)
        // In two buffers: the limit holds across the parts of a body, not per part.
        val half = size / 2
        val body: Flux<DataBuffer> = Flux.just(
            DefaultDataBufferFactory.sharedInstance.wrap(ByteArray(half)),
            DefaultDataBufferFactory.sharedInstance.wrap(ByteArray(size - half)),
        )
        val request = MockServerRequest.builder().method(HttpMethod.GET).uri(URI.create(PATH)).exchange(exchange)
            .body(body)
        val routes = CoSecControlRoutesAutoConfiguration.WebFlux().routesWithin(
            http,
            properties,
            ApplicationPort(),
            null,
            null,
            LIMIT.toLong(),
        )
        val response = routes.route(request).block()!!.handle(request).block()!!
        return response.statusCode().value() to response.toString()
    }

    private fun onWebMvc(size: Int): Int {
        val servlet = MockHttpServletRequest("GET", PATH)
        servlet.setContent(ByteArray(size))
        servlet.setSecurityContext(security.securityContext)
        val request = ServletServerRequest.create(servlet, listOf(ByteArrayHttpMessageConverter()))
        val routes = CoSecControlRoutesAutoConfiguration.WebMvc().routesWithin(http, properties, LIMIT.toLong())
        return routes.route(request).get().handle(request).statusCode().value()
    }

    @Test
    fun `a body within the limit reaches the control plane on both stacks, a larger one is a 400 that never does`() {
        onWebFlux(LIMIT).first.assert().isEqualTo(200)
        onWebMvc(LIMIT).assert().isEqualTo(200)
        coVerify(exactly = 2) { plane.read(any(), any(), any()) }
        onWebFlux(LIMIT + 1).first.assert().isEqualTo(400)
        onWebMvc(LIMIT + 1).assert().isEqualTo(400)
        coVerify(exactly = 2) { plane.read(any(), any(), any()) }
        CoSecControlRoutesAutoConfiguration.tooLargeProblem(LIMIT.toLong())["detail"].toString().assert()
            .contains("(16 bytes)").contains("CTL-41")
    }

    @Test
    fun `the limit is the document cap and 2 MB, 10 MB, and follows the cap`() {
        ControlDerived.MAX_BODY_BYTES.assert().isEqualTo(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES + 2L * 1024 * 1024)
        (ControlDerived.MAX_BODY_BYTES / (1024 * 1024)).assert().isEqualTo(10L)
        // DR1-F-17: the HTTP facade reads an answer up to twice what a route reads, so a change or a document always fits.
        HttpControlService.MAX_ANSWER_BYTES.assert().isEqualTo(2 * ControlDerived.MAX_BODY_BYTES)
        ControlDerived.MAX_BODY_BYTES.assert().isGreaterThan(ChangeOptions().maxDocumentBytes.toLong())
    }

    private companion object {
        const val PATH = "/cosec/control/v1/documents/tenant:acme"
        const val LIMIT = 16
    }
}
