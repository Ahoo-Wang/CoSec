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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.route.RouteMapCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** RFC 0005 §5.3: the action and resource of a request come from the service's route map, or stay `http`. */
class PipelineRoutesTest {
    private val routes = RouteMapCodec.load(
        listOf(
            "cosec-routes.json" to """
                {"service":"order","routes":[
                  {"method":"GET","path":"/orders/{orderId}","action":"order:read","resource":"order/{orderId}"}]}
            """.trimIndent().encodeToByteArray(),
        ),
    )

    @Test
    fun `a mapped request has its route's action and typed resource`() = runTest {
        val engine = StubEngine(PERMIT)
        pipeline(
            engine = engine
        ) { it.copy(routes = routes) }.evaluate(facts("/orders/42")).status.assert().isEqualTo(200)
        val request = engine.requests.single().request
        request.action.namespace.assert().isEqualTo("order")
        request.action.verb.assert().isEqualTo("read")
        request.resource.type.assert().isEqualTo("order")
        request.resource.id.assert().isEqualTo("42")
    }

    @Test
    fun `an unmapped request stays in the http namespace`() = runTest {
        val engine = StubEngine(PERMIT)
        pipeline(engine = engine) { it.copy(routes = routes) }.evaluate(facts("/customers/7"))
        val request = engine.requests.single().request
        request.action.namespace.assert().isEqualTo("http")
        request.action.verb.assert().isEqualTo("GET")
        request.resource.type.assert().isEqualTo("http")
        request.resource.id.assert().isEqualTo("/customers/7")
    }

    @Test
    fun `a capture holding a slash is refused, never read as another resource`() = runTest {
        val engine = StubEngine(PERMIT)
        val outcome = pipeline(engine = engine) { it.copy(routes = routes) }.evaluate(facts("/orders/a%2Fb"))
        outcome.status.assert().isEqualTo(400)
        outcome.problem!!.type.assert().isEqualTo(ProblemType.INVALID_REQUEST)
        engine.requests.assert().isEmpty()
    }
}
