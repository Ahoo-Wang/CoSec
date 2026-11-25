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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemRenderer
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.routeProblem
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `PIP-34`: `cosec.problem.render.failed` counts the refusals a renderer failed, each answered with CoSec's body. */
class ProblemRenderFailedMetricTest {
    @Test
    fun `each refusal a renderer failed is counted, and a rendered one is not`() {
        val registry = SimpleMeterRegistry()
        var broken = true
        val bodies = ProblemBodies(
            ProblemRenderer { if (broken) error("broken") else "{}".toByteArray() }
        )
        CoSecMeters.bind(registry, bodies)
        val problem = routeProblem(ProblemType.FORBIDDEN, "r")
        repeat(2) { bodies.body(problem) }
        broken = false
        bodies.body(problem)
        registry.find(CoSecMeters.PROBLEM_RENDER_FAILED).functionCounter()!!.count().assert().isEqualTo(2.0)
    }
}
