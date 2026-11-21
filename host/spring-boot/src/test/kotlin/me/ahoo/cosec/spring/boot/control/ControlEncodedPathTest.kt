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
import io.mockk.mockk
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.pipeline.ApplicationPort
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import me.ahoo.cosec.webmvc.ServletRequests.setSecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
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
 * #1385: on either stack, a path parameter reaches the control plane percent-decoded once, after the path is split into
 * segments; the adapters hand `ControlHttp` the path as the client wrote it. The routes are the real ones, and the key
 * is what `ControlPlane.read` is called with. A malformed escape never gets this far: Spring's path parsing refuses it
 * on both stacks (and the servers before it); `ControlHttp` answers 400 to one it is handed (`PathDecodingTest`).
 */
class ControlEncodedPathTest {
    private val keys = ArrayList<SnapshotKey>()
    private val plane = mockk<ControlPlane>().also { plane ->
        coEvery { plane.read(any(), any(), any()) } answers {
            val key = secondArg<SnapshotKey>()
            keys += key
            PolicyDocument(DocumentKind.TENANT, "acme", 1, Instant.parse("2026-11-01T00:00:00Z"), emptyList(), emptyList())
        }
    }
    private val http = ControlHttp(plane)
    private val properties = ControlProperties()
    private val security = permittedSecurity(DOCUMENTS)

    private fun onWebFlux(path: String): Int {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path))
        exchange.setSecurityContext(security.securityContext)
        val request = MockServerRequest.builder().method(HttpMethod.GET).uri(URI.create(path)).exchange(exchange)
            .body(Flux.empty<DataBuffer>())
        val routes = CoSecControlRoutesAutoConfiguration.WebFlux().cosecControlRoutes(
            http,
            properties,
            ApplicationPort()
        )
        return routes.route(request).block()!!.handle(request).block()!!.statusCode().value()
    }

    private fun onWebMvc(path: String): Int {
        val servlet = MockHttpServletRequest("GET", path)
        servlet.setSecurityContext(security.securityContext)
        val request = ServletServerRequest.create(servlet, listOf(ByteArrayHttpMessageConverter()))
        val routes = CoSecControlRoutesAutoConfiguration.WebMvc().cosecControlRoutes(http, properties)
        return routes.route(request).get().handle(request).statusCode().value()
    }

    private fun readOn(stack: (String) -> Int, parameter: String): Pair<Int, SnapshotKey?> {
        keys.clear()
        val status = stack("$DOCUMENTS$parameter")
        return status to keys.singleOrNull()
    }

    private val stacks = mapOf<String, (String) -> Int>("WebFlux" to ::onWebFlux, "WebMvc" to ::onWebMvc)

    @Test
    fun `a path parameter is decoded exactly once on both stacks`() {
        val expected = mapOf(
            "tenant:acme" to SnapshotKey.Tenant("acme"),
            "tenant:acme%20west" to SnapshotKey.Tenant("acme west"),
            "tenant:%E4%B8%AD%E6%96%87" to SnapshotKey.Tenant("中文"),
            "tenant:100%25" to SnapshotKey.Tenant("100%"),
            "tenant:a+b" to SnapshotKey.Tenant("a+b"),
            "tenant:a%2Bb" to SnapshotKey.Tenant("a+b"),
            "tenant:acme%2520west" to SnapshotKey.Tenant("acme%20west"),
            "tenant%3Aacme" to SnapshotKey.Tenant("acme"),
            "app%3Aorder" to SnapshotKey.App("order"),
        )
        stacks.forEach { (stack, call) ->
            expected.forEach { (parameter, key) ->
                readOn(call, parameter).assert().describedAs("$stack $parameter").isEqualTo(200 to key)
            }
            // Decoded once, an encoded `/` is part of the id, which no tenant id may hold (#1403): refused, unread.
            readOn(call, "tenant:acme%2Fwest").assert().describedAs("$stack acme%2Fwest").isEqualTo(400 to null)
        }
    }

    private companion object {
        const val DOCUMENTS = "/cosec/control/v1/documents/"
    }
}
