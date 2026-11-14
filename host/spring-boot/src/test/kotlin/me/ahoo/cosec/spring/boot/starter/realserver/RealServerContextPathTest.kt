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

package me.ahoo.cosec.spring.boot.starter.realserver

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.servlet.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * `PIP-20` over real servers: with a context path, the path CoSec decides on is the one the handler serves, however the
 * client wrote the context path. Jetty writes the context path canonically (`/app`) while the URI keeps the client's
 * `/%61pp`; removing it as text removed nothing, and `staff`, allowed everything, deleted an order past the DENY.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealServerContextPathTest {
    private val fixture = ParityFixture()
    private val servers = HashMap<Stack, RealServer>()

    @AfterAll
    fun close() {
        servers.values.forEach { it.close() }
    }

    private fun server(stack: Stack) = servers.getOrPut(stack) {
        RealServer(
            stack,
            fixture.gate(stack),
            RealServerRemoteIp(ServletXForwardedRemoteIpResolver(1), ReactiveXForwardedRemoteIpResolver(1)),
            contextPath = "/app",
        )
    }

    @ParameterizedTest
    @EnumSource(names = ["TOMCAT", "JETTY"])
    fun `the context path is not decided on, however it is written`(stack: Stack) {
        val server = server(stack)
        for (target in listOf("/app/orders/1", "/%61pp/orders/1", "/app;x=1/orders/1")) {
            val answer = server.send(raw("DELETE", target))
            answer.status.assert().describedAs("$stack $target: $answer").isEqualTo(403)
        }
        val served = server.send(raw("GET", "/%61pp/orders/1"))
        served.body.assert().describedAs("$stack: $served").startsWith("$SERVED GET /orders/1")
    }

    private fun raw(method: String, target: String) =
        "$method $target HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer bob@acme:staff\r\nConnection: close\r\n\r\n"
}
