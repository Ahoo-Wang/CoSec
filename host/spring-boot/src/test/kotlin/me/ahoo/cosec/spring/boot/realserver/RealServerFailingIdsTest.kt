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
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.webmvc.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `PIP-23` over real servers: an `IdGenerator` that throws (CosId's `ClockBackwardsException` after the clock went
 * back, a lost machine slot) never takes the answer with it. A random id names the request, the outcome is
 * `StatusMapping`'s, a problem body with its `CoSec-Request-Id` on every stack, and the decision event is published.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealServerFailingIdsTest {
    private val events = CopyOnWriteArrayList<DecisionEvent>()
    private val recorder = object : DecisionListener {
        override val name = "recorder"
        override val delivery = Delivery.REQUIRED

        override suspend fun onDecision(event: DecisionEvent) {
            events += event
        }
    }
    private val fixture = ParityFixture(
        IdGenerator { throw IllegalStateException("Clock moved backwards by 3000 ms") },
        DecisionListeners(listOf(recorder)),
    )
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

    private fun get(path: String) = "GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"

    @Test
    fun `a failing IdGenerator still answers through StatusMapping on every server`() {
        for ((stack, server) in servers) {
            events.clear()
            val refused = server.send(get("/orders/1"))
            val where = "$stack: $refused"
            refused.status.assert().describedAs(where).isEqualTo(401)
            refused.header("Content-Type").assert().describedAs(where).isEqualTo("application/problem+json")
            val requestId = refused.header("CoSec-Request-Id")!!
            requestId.assert().describedAs(where).matches(UUID)
            refused.body.assert().describedAs(where).contains("\"requestId\":\"$requestId\"")
            events.single().requestId.assert().describedAs(where).isEqualTo(requestId)
            val permitted = server.send(get("/public/page"))
            permitted.status.assert().describedAs("$stack: $permitted").isEqualTo(200)
            permitted.header("CoSec-Request-Id")!!.assert().matches(UUID)
        }
    }

    private companion object {
        /** What `RandomIdGenerator` makes: a version 4 UUID. */
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").toPattern()
    }
}
