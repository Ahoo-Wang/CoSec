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
package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Decision
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * The request-local cache of policy conditions (#1212): each condition runs once per evaluation, found again by identity.
 * `policies` policies bound to `Anyone`, each compiled apart, so each holds its own condition object whatever its text,
 * as the document compiler leaves them; each condition is `subject.id == "nobody"`, and each policy holds one DENY that
 * applies. The request is alice's, so every condition is reached, fails, and the decision is the implicit deny. How the
 * lookup grows with the number of distinct conditions a request reaches is what the benchmark shows.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class PolicyConditionBenchmark {
    @Param("4", "16", "32", "64", "128", "512", "2048")
    var policies: Int = 0

    private lateinit var request: FlatRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val compiler = PolicyCompiler()
        val compiled = (0 until policies).map {
            val condition = Condition.Eq("subject.id", Literal.Str("nobody"))
            compiler.compile(PolicyId("p$it"), condition, listOf(StatementSource("deny", Effect.DENY, listOf("*"))))
        }
        view = SnapshotView(CompiledDocument.platform(compiled, compiled.map(::anyoneBinding)), null, emptyTenant())
        request = benchmarkRequest(Action("http", "GET"), Resource("http", "/anything", emptyMap()), subjectId = "alice")
        val decision = evaluate()
        check(decision == Decision.Deny(null)) { "Decided $decision" }
    }

    @Benchmark
    fun evaluate(): Decision = CoSecFlatEngine.evaluate(request, view).decision
}
