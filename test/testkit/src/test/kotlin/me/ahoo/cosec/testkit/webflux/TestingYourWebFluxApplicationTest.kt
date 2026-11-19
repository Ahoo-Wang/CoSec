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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit.webflux

import io.mockk.mockk
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.cosec.testkit.PermittedRequest
import me.ahoo.cosec.testkit.PipelineFixture
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.requireSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.securityContextOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.util.context.Context

/**
 * The samples of "Testing your application" (`wiki/policies/testing.md`), WebFlux side, and `PIP-29`: the kit's
 * contexts behind CoSec's own filter, or bound by hand for a unit test, and a hand-made context refused.
 */
class TestingYourWebFluxApplicationTest {
    private val documents = """{"policies":[{"id":"reads","statements":[""" +
        """{"effect":"allow","action":"http:GET","resource":"http:path:/orders/**"}]}],""" +
        """"bindings":[{"id":"reads","subject":{"role":"reader"},"app":"*","space":"*","policy":"reads"}]}"""

    /** The handler under test: it answers who is calling. */
    private val routes = RouterFunctions.route().GET("/orders/{id}") { request ->
        ServerResponse.ok().bodyValue(request.exchange().securityContextOrNull()?.principal?.id ?: "none")
    }.build()

    @Test
    fun `a handler behind CoSec's filter over the kit's documents`() {
        PipelineFixture.of(tenants = mapOf("acme" to documents)).use { fixture ->
            val client = WebTestClient.bindToRouterFunction(routes)
                .webFilter<WebTestClient.RouterFunctionSpec>(CoSecTestBindings.filter(fixture.pipeline))
                .build()
            val alice = PipelineFixture.Principal("alice", tenant = "acme", roles = setOf("reader"))
            client.get().uri("/orders/1").header("Authorization", fixture.authorization(alice)).exchange()
                .expectStatus().isOk.expectBody(String::class.java).isEqualTo("alice")
            client.get().uri("/orders/1").exchange().expectStatus().isUnauthorized
        }
    }

    @Test
    fun `a service behind a gateway, with the header a gateway forwards`() {
        val client = WebTestClient.bindToRouterFunction(routes)
            .webFilter<WebTestClient.RouterFunctionSpec>(CoSecTestBindings.filter(Pipeline.behindGateway()))
            .build()
        client.get().uri("/orders/1").header(IdentityHeader.NAME, IdentityHeader.forSubject("alice", "acme")).exchange()
            .expectStatus().isOk.expectBody(String::class.java).isEqualTo("alice")
    }

    /** A function under test that reads the caller from the Reactor context. */
    private fun currentUser(): Mono<String> =
        Mono.deferContextual { Mono.just(it.requireSecurityContext().principal.id) }

    @Test
    fun `a function that reads the Reactor context, with a context the kit made`() {
        val alice = PermittedRequest.forSubject("alice", tenant = "acme", roles = setOf("reader"))
        StepVerifier.create(CoSecTestBindings.withSecurityContext(currentUser(), alice))
            .expectNext("alice").verifyComplete()
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/1"))
        CoSecTestBindings.bind(exchange, alice)
        exchange.securityContextOrNull().assert().isSameAs(alice)
    }

    /** Coroutine code under test reads the context through the Mono it awaits, with no `mono { }.block()` of its own. */
    @Test
    fun `a suspending function that awaits a Mono, with a context the kit made`() = runBlocking<Unit> {
        val alice = PermittedRequest.forSubject("alice", tenant = "acme", roles = setOf("reader"))
        CoSecTestBindings.withSecurityContext(alice) { currentUser().awaitSingle() }.assert().isEqualTo("alice")
        CoSecTestBindings.withSecurityContext(alice) { mono { currentUser().awaitSingle() }.awaitSingle() }
            .assert().isEqualTo("alice")
        assertThrownBy<IllegalArgumentException> {
            runBlocking { CoSecTestBindings.withSecurityContext(mockk<SecurityContext>()) { "never" } }
        }.hasMessageContaining("PIP-27")
        // A Reactor context the coroutine already has is kept: the context is added to it, not put in its place.
        withContext(Context.of("tenant-hint", "acme").asCoroutineContext()) {
            CoSecTestBindings.withSecurityContext(alice) {
                val both = Mono.deferContextual { context ->
                    val hint = context.get<String>("tenant-hint")
                    Mono.just(hint + ":" + context.requireSecurityContext().principal.id)
                }
                both.awaitSingle()
            }
        }.assert().isEqualTo("acme:alice")
    }

    @Test
    fun `a context made by hand is refused, never bound as anonymous`() {
        val forged = mockk<SecurityContext>()
        assertThrownBy<IllegalArgumentException> { CoSecTestBindings.withSecurityContext(currentUser(), forged) }
            .hasMessageContaining("PermittedRequest")
        assertThrownBy<IllegalArgumentException> {
            CoSecTestBindings.bind(MockServerWebExchange.from(MockServerHttpRequest.get("/")), forged)
        }.hasMessageContaining("PIP-27")
    }
}
