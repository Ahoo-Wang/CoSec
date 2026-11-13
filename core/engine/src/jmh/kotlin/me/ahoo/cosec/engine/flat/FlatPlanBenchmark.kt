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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * `CoSecFlatEngine.plan`: what the pipeline asks the engine before it fetches external attributes (RFC 0006 §5.4.2),
 * once per request of a deployment whose documents read one (#1211; the other benchmarks of this package never call it).
 * One policy of `statements` ALLOWs on `http:GET` and the path `/private<n>` and below, each with the condition
 * `ext.check.ok == true`, bound to `Anyone`. The request is
 *
 * - `path-miss`: `GET /outside`, which no path matches: nothing to fetch;
 * - `action-miss`: `POST /private0/x`, which no action matches: nothing to fetch;
 * - `hit`: `GET /private<last>/x`, which one statement matches: `ext.check.ok`;
 * - `wide`: every statement's path is any path below `/`, so the index rules none out and the plan reads every statement.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class FlatPlanBenchmark {
    @Param("path-miss", "action-miss", "hit", "wide")
    lateinit var scenario: String

    @Param("16", "1024", "10000")
    var statements: Int = 0

    private lateinit var request: FlatRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val check = Attribute.external("ext.check.ok", AttributeType.BOOL)
        val compiler = PolicyCompiler(ConditionChecker(catalog = AttributeCatalog.of(listOf(check))))
        val condition = Condition.Eq(check.path, Literal.Bool(true))
        val sources = (0 until statements).map {
            val pattern = if (scenario == "wide") "/**" else "/private$it/**"
            StatementSource("s$it", Effect.ALLOW, listOf("http:GET"), listOf(ResourceSource("http:path:$pattern")), condition)
        }
        val policy = compiler.compile(PolicyId("p"), null, sources)
        view = SnapshotView(CompiledDocument.platform(listOf(policy), listOf(anyoneBinding(policy))), null, emptyTenant())
        request = when (scenario) {
            "path-miss" -> benchmarkRequest(Action("http", "GET"), Resource("http", "/outside", emptyMap()))
            "action-miss" -> benchmarkRequest(Action("http", "POST"), Resource("http", "/private0/x", emptyMap()))
            "hit" -> benchmarkRequest(Action("http", "GET"), Resource("http", "/private${statements - 1}/x", emptyMap()))
            "wide" -> benchmarkRequest(Action("http", "GET"), Resource("http", "/anything", emptyMap()))
            else -> error("Unknown scenario $scenario")
        }
        val plan = plan()
        check(plan.attributes.isEmpty() || plan.attributes == listOf(check)) { "Scenario $scenario planned $plan" }
    }

    @Benchmark
    fun plan(): AttributePlan = CoSecFlatEngine.plan(request, view)
}
