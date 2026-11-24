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

package me.ahoo.cosec.spring.boot.control

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.Problem
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemRenderer
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * `PIP-34` on a Spring Cloud Gateway host (DR1-C-7d): the control routes pass a [me.ahoo.cosec.webflux.PipelineWebFilter]
 * of their own, and its refusals carry the host's body, as the gateway filter's do, not CoSec's default.
 */
class GatewayHostProblemBodyTest {
    @Test
    fun `the control routes of a gateway host render a refusal with the host's renderer`() {
        val problem = mockk<Problem>()
        val refusal = mockk<Outcome> {
            every { status } returns 403
            every { this@mockk.problem } returns problem
            every { headers } returns emptyMap()
        }
        val pipeline = mockk<Pipeline> { coEvery { evaluate(any()) } returns refusal }
        val context = GenericApplicationContext().apply { refresh() }
        val bodies = ProblemBodies(ProblemRenderer { """{"type":"urn:host:error"}""".toByteArray() })
        val filter = CoSecControlRoutesAutoConfiguration.GatewayHost().cosecControlPipelineWebFilter(
            pipeline,
            context.getBeanProvider(BridgeContextContributor::class.java),
            RuntimeAuthorizationProperties(),
            ControlProperties(),
            context,
            bodies,
        )
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/cosec/control/changes"))
        filter.filter(exchange, WebFilterChain { Mono.empty() }).block()
        exchange.response.statusCode!!.value().assert().isEqualTo(403)
        exchange.response.bodyAsString.block().assert().isEqualTo("""{"type":"urn:host:error"}""")
    }
}
