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

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebHandler
import org.springframework.web.server.handler.FilteringWebHandler
import java.net.URI

/**
 * The WebFlux adapter over the assembled 8.0 path (RFC 0006 PR-7): HTTP through `WebTestClient`, the filter, the
 * coroutine pipeline, the snapshot source and real Redis. What a client sees: the handler runs only for a permit and
 * reads the validated scope; every refusal is a problem with the request id.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebFluxSliceTest {
    private val slice = Slice()

    private val client: WebTestClient by lazy {
        val handler = WebHandler { exchange ->
            val scope = exchange.getSecurityContext()?.scope
            val body = "${scope?.appId}/${scope?.spaceId}/${scope?.roles}".toByteArray()
            exchange.response.writeWith(reactor.core.publisher.Mono.just(exchange.response.bufferFactory().wrap(body)))
        }
        val filter = PipelineWebFilter(slice.pipeline, host = HostScope(AppId("order")))
        WebTestClient.bindToWebHandler(FilteringWebHandler(handler, listOf(filter))).build()
    }

    @BeforeAll
    fun publish() {
        slice.publish(
            SnapshotKey.Platform,
            ""","policies":[{"id":"no-delete","statements":[{"effect":"deny","action":"http:DELETE","resource":"http:path:/orders/**"}]}],""" +
                """"bindings":[{"id":"g:no-delete","subject":"*","app":"*","space":"*","policy":"no-delete"}]"""
        )
        slice.publish(
            SnapshotKey.App("order"),
            ""","catalog":{"groups":[{"name":"order","permissions":[{"id":"read"}]}]},""" +
                """"policies":[{"id":"perm:order/read","statements":[{"effect":"allow","action":"http:*","resource":"http:path:/orders/**"}]}],""" +
                """"bindings":[{"id":"r:clerk:read","subject":{"role":"clerk"},"app":"order","space":"*","policy":"perm:order/read"}]"""
        )
        slice.publish(SnapshotKey.Tenant("acme"), "")
    }

    @AfterAll
    fun close() {
        slice.close()
    }

    @Test
    fun `a permitted request reaches the handler with its validated scope`() {
        client.get().uri("/orders/1").header("Authorization", "Bearer alice@acme:clerk").exchange()
            .expectStatus().isOk
            .expectHeader().exists("CoSec-Request-Id")
            .expectBody(String::class.java).isEqualTo("order//[clerk]")
    }

    @Test
    fun `a refused request is a problem, and the handler never runs`() {
        client.delete().uri("/orders/1").header("Authorization", "Bearer alice@acme:clerk")
            .header("CoSec-Request-Id", "e2e-1").exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType(MediaType.parseMediaType("application/problem+json"))
            .expectHeader().valueEquals("CoSec-Request-Id", "e2e-1")
            .expectBody(String::class.java).isEqualTo(
                """{"type":"https://cosec.ahoo.me/problems/forbidden","title":"Forbidden","status":403,""" +
                    """"code":"FORBIDDEN","requestId":"e2e-1"}"""
            )
        client.get().uri("/orders/1").exchange().expectStatus().isUnauthorized
        client.get().uri(URI.create("/orders/%2e%2e/admin")).exchange().expectStatus().isBadRequest
            .expectHeader().exists("CoSec-Request-Id")
    }
}
