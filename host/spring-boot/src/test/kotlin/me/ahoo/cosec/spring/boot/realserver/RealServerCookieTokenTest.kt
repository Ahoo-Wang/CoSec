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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosec.webmvc.ServletXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A token in the `authorization` cookie authenticates alike on every server, wrapped in double quotes or not
 * (R2B-25): Tomcat hands the quotes over, Jetty and Reactor Netty drop them, and the readers read the value without
 * them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealServerCookieTokenTest {
    private val fixture = ParityFixture(cookieTokens = true)
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

    @ParameterizedTest
    @ValueSource(strings = ["alice@acme:clerk", "\"alice@acme:clerk\""])
    fun `a cookie token authenticates on every server, quoted or not`(cookie: String) {
        for ((stack, server) in servers) {
            val answer = server.send(
                "GET /orders/1 HTTP/1.1\r\nHost: localhost\r\nCookie: authorization=$cookie\r\nConnection: close\r\n\r\n"
            )
            answer.status.assert().describedAs("$stack: $answer").isEqualTo(200)
            if (stack != Stack.GATEWAY) answer.body.assert().describedAs("$stack: $answer").endsWith("principal=alice")
        }
    }
}
