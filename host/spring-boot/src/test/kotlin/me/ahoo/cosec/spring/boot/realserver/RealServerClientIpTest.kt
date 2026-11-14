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
import me.ahoo.cosec.spring.boot.starter.pipeline.ClientIpReadings
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.web.server.autoconfigure.ServerProperties

/**
 * `PIP-16` over real servers: on every stack, with each forward-headers strategy Spring Boot applies to it, the client
 * address CoSec decides on is the one the outermost trusted proxy saw, whatever the client wrote. The suite connects
 * from 127.0.0.1, which Tomcat's `RemoteIpValve` counts as an internal proxy: the request stands for one a single proxy
 * forwarded, as `max-trusted-index: 1` (the default) assumes.
 *
 * The documents allow the paths under `/internal` from `10.0.0.0/8` only and deny those under `/guarded` to
 * `192.0.2.0/24`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealServerClientIpTest {
    private val fixture = ParityFixture()
    private val servers = HashMap<Pair<Stack, ServerProperties.ForwardHeadersStrategy>, RealServer>()

    @AfterAll
    fun close() {
        servers.values.forEach { it.close() }
    }

    private fun server(stack: Stack, strategy: ServerProperties.ForwardHeadersStrategy): RealServer =
        servers.getOrPut(stack to strategy) {
            val reading = ClientIpReadings.decide(
                ClientIpReadings.Strategy.valueOf(strategy.name),
                serverOf(stack),
                maxTrustedIndex = 1,
                explicit = false,
            )
            RealServer(
                stack,
                fixture.gate(stack),
                RealServerRemoteIp(
                    ServletXForwardedRemoteIpResolver(reading.maxTrustedIndex, reading.peerIsConnection),
                    ReactiveXForwardedRemoteIpResolver(reading.maxTrustedIndex, reading.peerIsConnection),
                ),
                serverProperties = ServerProperties().apply { forwardHeadersStrategy = strategy },
            )
        }

    fun stacks(): List<Arguments> = Stack.entries.flatMap { stack ->
        listOf(ServerProperties.ForwardHeadersStrategy.NONE, ServerProperties.ForwardHeadersStrategy.NATIVE)
            .map { Arguments.of(stack, it) }
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("stacks")
    fun `a client cannot claim an internal address`(stack: Stack, strategy: ServerProperties.ForwardHeadersStrategy) {
        val server = server(stack, strategy)
        // The client 203.0.113.7 wrote 10.6.6.6; the proxy appended the address it saw.
        val forged = server.send(get("/internal/x", "X-Forwarded-For: 10.6.6.6, 203.0.113.7"))
        forged.status.assert().describedAs("$stack $strategy: $forged").isEqualTo(401)
        val claimed = server.send(get("/internal/x", "X-Forwarded-For: 203.0.113.7", "Forwarded: for=10.6.6.6"))
        claimed.status.assert().describedAs("$stack $strategy: $claimed").isEqualTo(401)
        val internal = server.send(get("/internal/x", "X-Forwarded-For: 10.1.2.3"))
        internal.status.assert().describedAs("$stack $strategy: $internal").isEqualTo(200)
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("stacks")
    fun `a denied client cannot claim another address`(
        stack: Stack,
        strategy: ServerProperties.ForwardHeadersStrategy
    ) {
        val server = server(stack, strategy)
        val denied = server.send(get("/guarded/x", "X-Forwarded-For: 192.0.2.5"))
        denied.status.assert().describedAs("$stack $strategy: $denied").isEqualTo(401)
        val forged = server.send(get("/guarded/x", "X-Forwarded-For: 203.0.113.1, 192.0.2.5"))
        forged.status.assert().describedAs("$stack $strategy: $forged").isEqualTo(401)
        val other = server.send(get("/guarded/x", "X-Forwarded-For: 203.0.113.1"))
        other.status.assert().describedAs("$stack $strategy: $other").isEqualTo(200)
    }

    private fun get(path: String, vararg headers: String) =
        "GET $path HTTP/1.1\r\nHost: localhost\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n"

    private companion object {
        fun serverOf(stack: Stack): ClientIpReadings.Server = when (stack) {
            Stack.TOMCAT, Stack.TOMCAT_REACTIVE -> ClientIpReadings.Server.TOMCAT
            Stack.JETTY, Stack.JETTY_REACTIVE -> ClientIpReadings.Server.JETTY
            Stack.NETTY, Stack.GATEWAY -> ClientIpReadings.Server.NETTY
        }
    }
}
