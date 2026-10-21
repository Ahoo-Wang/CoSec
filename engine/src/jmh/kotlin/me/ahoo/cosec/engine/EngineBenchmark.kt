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

package me.ahoo.cosec.engine

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.Subject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * The engine alone, on test rules that compare the request path and allocate nothing (RFC 0002 §7.1).
 *
 * `shape` is `groups x statements` of the global layer: 10 groups of 10, 100 or 1,000 statements, and 64, 65 or 640
 * groups of 10. Half of each group's statements deny. The principal layer holds one policy and the role layer one app
 * with two roles, neither matching, so every scenario but `deny` evaluates all three layers.
 *
 * Scenarios:
 * - `allow`: the last ALLOW of the last global group matches: both passes scan the whole global layer
 * - `deny`: the last DENY of the last global group matches
 * - `none`: nothing matches, an implicit deny
 *
 * `impl`: `off` is [CoSecEngine] at [TraceLevel.OFF]; `control` is [ControlEngine], the same algorithm without trace
 * code; `full` is [CoSecEngine] at [TraceLevel.FULL], for reference only.
 *
 * Expected allocation for `off`: independent of `statements`, and growing with `groups` by no more than the guard
 * bitmap (none up to 64 groups, a `LongArray` beyond).
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class EngineBenchmark {
    @Param("allow", "deny", "none")
    lateinit var scenario: String

    @Param("10x10", "10x100", "10x1000", "64x10", "65x10", "640x10")
    lateinit var shape: String

    @Param("off", "control", "full")
    lateinit var impl: String

    private lateinit var request: AuthzRequest
    private lateinit var policies: PolicySet
    private lateinit var engine: (AuthzRequest, PolicySet) -> Evaluation

    @Setup
    fun setup() {
        val (groupCount, statementCount) = shape.split("x").map { it.toInt() }
        val global = (0 until groupCount).map { g ->
            BenchmarkGroup("global$g", (0 until statementCount).map { s -> PathRule("/g$g/s$s", effectOf(s), s) })
        }
        val principal = BenchmarkGroup("principal", (0 until 10).map { PathRule("/principal/s$it", effectOf(it), it) })
        val app = BenchmarkGroup(
            "app",
            (0 until 10).map { PathRule("/app/p$it", effectOf(it), it / 2, id = "p$it") },
            roles = listOf("viewer", "editor"),
        )
        policies = PolicySet(
            listOf(
                LayerInput.Loaded(LayerKind.GLOBAL, global),
                LayerInput.Loaded(LayerKind.PRINCIPAL, listOf(principal)),
                LayerInput.Loaded(LayerKind.ROLE, listOf(app)),
            ),
        )
        val last = groupCount - 1
        // Statement s denies when s is even: the last statement of each effect.
        val lastDeny = (statementCount - 1) / 2 * 2
        val lastAllow = if (statementCount % 2 == 0) statementCount - 1 else statementCount - 2
        val path = when (scenario) {
            "allow" -> "/g$last/s$lastAllow"
            "deny" -> "/g$last/s$lastDeny"
            "none" -> "/nothing"
            else -> error("Unknown scenario $scenario")
        }
        request = request(path)
        engine = when (impl) {
            "off" -> { request, policies -> CoSecEngine.evaluate(request, policies, TraceLevel.OFF) }
            "control" -> { request, policies -> ControlEngine.evaluate(request, policies) }
            "full" -> { request, policies -> CoSecEngine.evaluate(request, policies, TraceLevel.FULL) }
            else -> error("Unknown impl $impl")
        }
        // Also loads the tracing evaluator, as an application that explains decisions would, so its hooks are not
        // the only implementation the JIT has seen.
        check(engine(request, policies).decision == CoSecEngine.evaluate(request, policies, TraceLevel.FULL).decision)
    }

    @Benchmark
    fun evaluate(): Evaluation = engine(request, policies)

    private fun effectOf(statement: Int) = if (statement % 2 == 0) Effect.DENY else Effect.ALLOW

    private fun request(path: String) =
        AuthzRequest(
            subject = Subject("user", "tenant", true, setOf("viewer", "editor"), setOf("principal"), emptyMap(), root = false),
            scope = Scope.declared("tenant", "app", "default"),
            action = Action("http", "GET"),
            resource = Resource("http", path, emptyMap()),
            environment = Environment("127.0.0.1", "request", "device", blocked = false),
        )
}

private object AlwaysGuard : Guard {
    override fun matches(request: AuthzRequest): Boolean = true
}

private class PathRule(
    private val path: String,
    override val effect: Effect,
    override val index: Int,
    override val id: String? = null,
) : Rule {
    override val name: String = path

    override fun match(request: AuthzRequest): RuleMatch =
        if (request.resource.id == path) RuleMatch.MATCHED else RuleMatch.ACTION_MISS
}

/** Without [roles], a policy; with them, an app whose rules are granted round-robin through the roles. */
private class BenchmarkGroup(
    override val id: String,
    private val rules: List<Rule>,
    private val roles: List<String> = emptyList(),
) : RuleGroup {
    override val guard: Guard = AlwaysGuard

    override fun forEachRule(visitor: RuleVisitor): Boolean {
        for (i in rules.indices) {
            val via = if (roles.isEmpty()) null else roles[i % roles.size]
            if (visitor.visit(rules[i], via)) {
                return true
            }
        }
        return false
    }
}
