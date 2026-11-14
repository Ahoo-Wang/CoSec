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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosec.webmvc.ServletXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * One decision path over real servers (`PIP-1`): Tomcat, Jetty and Reactor Netty, as Spring Boot builds them, and the
 * gateway's filter, answer each request, sent as raw bytes, alike: status, `Content-Type`, `CoSec-Request-Id`, the
 * rate limit headers and the body. `WebAdapterParityTest` checks the adapters over the facts a mock hands them; this
 * suite checks the facts each server hands its adapter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealServerParityTest {
    private val fixture = ParityFixture()
    private val servers = Stack.entries.associateWith {
        RealServer(
            it,
            fixture.gate(it),
            RealServerRemoteIp(ServletXForwardedRemoteIpResolver(1), ReactiveXForwardedRemoteIpResolver(1)),
        )
    }

    @AfterAll
    fun close() {
        servers.values.forEach { it.close() }
        fixture.close()
    }

    /**
     * A request, and what every stack must answer to it: [status], and on a permit what the handler was [served]. The
     * stacks in [refusedFirst] are servers that refuse the request themselves, with the same status, before any filter
     * runs (Jetty's URI compliance): the request never reaches CoSec there, which is as safe.
     */
    class Scenario(
        val name: String,
        val request: String,
        val status: Int,
        val served: String? = null,
        val refusedFirst: Set<Stack> = emptySet(),
    ) {
        override fun toString(): String = name
    }

    fun scenarios(): List<Scenario> = listOf(
        Scenario("a public page is served", get("/public/page"), 200, "GET /public/page"),
        Scenario("an anonymous request is 401", get("/orders/1"), 401),
        Scenario(
            "a role grant permits",
            get("/orders/1", "Authorization: Bearer alice@acme:clerk"),
            200,
            "GET /orders/1"
        ),
        Scenario("a DENY is 403", raw("DELETE", "/orders/1", "Authorization: Bearer alice@acme:clerk"), 403),
        Scenario("a client's request id is answered", get("/orders/1", "CoSec-Request-Id: client-id-1"), 401),
        Scenario(
            "a client's identity never reaches the handler",
            get("/public/page", "CoSec-Identity: forged"),
            200,
            "GET /public/page"
        ),
        Scenario(
            "conflicting CoSec parameters are 400",
            get("/public/page", "CoSec-Space-Id: a", "CoSec-Space-Id: b"),
            400
        ),
        Scenario("an exhausted rate limit is 429", get("/public/limited/1"), 429),
        Scenario("an encoded dot segment is 400", get("/public/%2e%2e/orders/1"), 400, refusedFirst = JETTY_STACKS),
        Scenario("an empty segment is 400", get("/public//page"), 400, refusedFirst = JETTY_STACKS),
        Scenario("a path parameter is removed", get("/public;x=1/page"), 200, "GET /public/page"),
    )

    @ParameterizedTest
    @MethodSource("scenarios")
    fun `every server answers alike`(scenario: Scenario) {
        val answers = servers.mapValues { (_, server) -> server.send(scenario.request) }
        val reference = answers.getValue(Stack.NETTY)
        for ((stack, answer) in answers) {
            val where = "$stack, ${scenario.name}: $answer"
            answer.status.assert().describedAs(where).isEqualTo(scenario.status)
            if (stack in scenario.refusedFirst) {
                answer.header("CoSec-Request-Id").assert().describedAs(where).isNull()
                continue
            }
            ParityFixture.comparable(answer).assert().describedAs(where).isEqualTo(ParityFixture.comparable(reference))
            if (scenario.served != null) {
                answer.body.assert().describedAs(where).startsWith("$SERVED ${scenario.served} identity=null")
            }
        }
    }

    private companion object {
        val JETTY_STACKS = setOf(Stack.JETTY, Stack.JETTY_REACTIVE)
    }

    private fun get(path: String, vararg headers: String) = raw("GET", path, *headers)

    private fun raw(method: String, path: String, vararg headers: String) =
        "$method $path HTTP/1.1\r\nHost: localhost\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n"
}
