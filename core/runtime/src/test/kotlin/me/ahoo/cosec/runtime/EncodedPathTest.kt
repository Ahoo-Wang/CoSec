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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.http.Basis
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `PIP-30`: a path holding an encoded `;` or control character is 400 before authentication, mapped route or not, and
 * makes its event as every 400 of the first stage does.
 */
class EncodedPathTest {
    private val routes = RouteMapCodec.load(
        listOf(
            "cosec-routes.json" to """
                {"service":"order","routes":[
                  {"method":"GET","path":"/orders/{orderId}","action":"order:read","resource":"order/{orderId}"}]}
            """.trimIndent().encodeToByteArray(),
        ),
    )

    private val events = CopyOnWriteArrayList<DecisionEvent>()
    private val recorder = object : DecisionListener {
        override val name = "recorder"
        override val delivery = Delivery.REQUIRED

        override suspend fun onDecision(event: DecisionEvent) {
            events += event
        }
    }

    @Test
    fun `an encoded semicolon or control character is refused before authentication, with its event`() = runTest {
        for (path in listOf("/admin%3Bx/users", "/orders/4%3b2", "/orders/1%00", "/orders/%1F", "/orders/1%7f")) {
            events.clear()
            var authenticated = false
            val engine = StubEngine(PERMIT)
            val authenticator = Authenticator {
                authenticated = true
                AuthnResult.Anonymous
            }
            val outcome = pipeline(authenticator, engine) {
                routes(routes)
                listeners(DecisionListeners(listOf(recorder)))
            }.evaluate(facts(path = path))
            outcome.status.assert().describedAs(path).isEqualTo(400)
            outcome.problem!!.type.assert().isEqualTo(ProblemType.INVALID_REQUEST)
            authenticated.assert().describedAs(path).isFalse()
            engine.requests.assert().isEmpty()
            events.single().kind.assert().isEqualTo(DecisionEvent.Kind.BAD_REQUEST)
            val reason = (outcome.basis as Basis.InvalidRequest).reason
            reason.assert().startsWith("path: an encoded ").contains(" in segment ").doesNotContain(path)
        }
    }

    @Test
    fun `a double-encoded semicolon is text of its segment, and the request is decided`() = runTest {
        val engine = StubEngine(PERMIT)
        pipeline(engine = engine) { routes(routes) }.evaluate(facts(path = "/orders/4%253B2")).status.assert()
            .isEqualTo(200)
        engine.requests.single().request.resource.id.assert().isEqualTo("4%3B2")
    }
}
