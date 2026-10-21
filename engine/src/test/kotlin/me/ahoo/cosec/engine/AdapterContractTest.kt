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

import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Effect.ALLOW
import me.ahoo.cosec.model.Effect.DENY
import me.ahoo.cosec.model.LayerKind.GLOBAL
import me.ahoo.cosec.model.LayerKind.PRINCIPAL
import me.ahoo.cosec.model.LayerKind.ROLE
import me.ahoo.cosec.model.StatementRef
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import me.ahoo.cosec.engine.GuardBehavior as G
import me.ahoo.cosec.engine.RuleBehavior as R

/**
 * A group that breaks the [RuleGroup] contract fails the evaluation instead of deciding: the decision could not be
 * trusted. Also how errors thrown only for the trace are handled.
 */
class AdapterContractTest {
    private val fixture = Fixture()

    private fun evaluate(policies: PolicySet, level: TraceLevel) = CoSecEngine.evaluate(request(), policies, level)

    /** Returns true from [forEachRule] without its visitor having asked to stop. */
    private class LyingGroup(override val id: String, override val guard: Guard) : RuleGroup {
        override fun forEachRule(visitor: RuleVisitor): Boolean = true
    }

    /** Keeps visiting after its visitor asked to stop, and says it was not stopped. */
    private class StubbornGroup(private val group: TestGroup) : RuleGroup by group {
        override fun forEachRule(visitor: RuleVisitor): Boolean {
            group.grants.forEach { visitor.visit(it.rule, it.via) }
            return false
        }
    }

    private fun throwing(name: String, effect: Effect, error: () -> Throwable): Rule =
        object : Rule by fixture.rule(name, effect) {
            override fun match(request: AuthzRequest): RuleMatch = throw error()
        }

    @Test
    fun `a group claiming its visitor stopped fails the evaluation at every level`() {
        // Trusting it would skip the principal layer's deny and permit.
        val policies = PolicySet(
            listOf(
                loaded(GLOBAL, fixture.policy("g", ALLOW to R.MATCH)),
                loaded(PRINCIPAL, LyingGroup("liar", fixture.guard("liar")), fixture.policy("p", DENY to R.MATCH)),
            ),
        )
        TraceLevel.entries.forEach { level ->
            assertThrownBy<IllegalStateException> { evaluate(policies, level) }
        }
    }

    @Test
    fun `an untraced group that ignores its visitor's stop fails the evaluation`() {
        val stubborn = StubbornGroup(fixture.policy("p", DENY to R.MATCH, DENY to R.MATCH))
        assertThrownBy<IllegalStateException> {
            evaluate(PolicySet(listOf(loaded(GLOBAL, stubborn))), TraceLevel.OFF)
        }
        // Traced, the visitor never asks to stop, so the group keeps the contract.
        evaluate(PolicySet(listOf(loaded(GLOBAL, stubborn))), TraceLevel.FULL).decision
            .assert().isEqualTo(Decision.Deny(StatementRef.PolicyStatement(GLOBAL, "p", 0, "p.0")))
    }

    @Test
    fun `a role-layer rule without a permission id fails the evaluation at every level`() {
        // The broken entry comes after the matching allow, which untraced alone would have permitted.
        val app = TestGroup(
            "app",
            fixture.guard("app"),
            listOf(
                Grant(fixture.rule("a", ALLOW, id = "a"), "admin"),
                Grant(fixture.rule("b", ALLOW, id = null), "admin"),
            ),
        )
        TraceLevel.entries.forEach { level ->
            assertThrownBy<IllegalStateException> { evaluate(PolicySet(listOf(loaded(ROLE, app))), level) }
        }
    }

    @Test
    fun `a role-layer entry without its granting role fails the evaluation at every level`() {
        val app = TestGroup(
            "app",
            fixture.guard("app"),
            listOf(Grant(UnresolvedGrant("gone", IllegalStateException())))
        )
        TraceLevel.entries.forEach { level ->
            assertThrownBy<IllegalStateException> { evaluate(PolicySet(listOf(loaded(ROLE, app))), level) }
        }
    }

    @Test
    fun `a policy rule visited with a granting role fails the evaluation at every level`() {
        val group = TestGroup(
            "p",
            fixture.guard("p"),
            listOf(Grant(fixture.rule("r", ALLOW, R.ACTION_MISS), via = "admin"))
        )
        TraceLevel.entries.forEach { level ->
            assertThrownBy<IllegalStateException> { evaluate(PolicySet(listOf(loaded(GLOBAL, group))), level) }
        }
    }

    @Test
    fun `an error from a rule evaluated only for the trace is recorded`() {
        val todo = NotImplementedError()
        val group = TestGroup(
            "g",
            fixture.guard("g"),
            listOf(Grant(fixture.rule("d", DENY)), Grant(throwing("x", DENY) { todo })),
        )
        val exhaustiveLayer = TestGroup("p", fixture.guard("p"), listOf(Grant(throwing("y", ALLOW) { todo })))
        val policies = PolicySet(listOf(loaded(GLOBAL, group), loaded(PRINCIPAL, exhaustiveLayer)))
        val denied = Decision.Deny(StatementRef.PolicyStatement(GLOBAL, "g", 0, "d"))
        TraceLevel.entries.forEach { level ->
            val evaluation = evaluate(policies, level)
            evaluation.decision.assert().isEqualTo(denied)
            evaluation.trace?.layers?.get(0)?.groups?.single()?.rules?.get(1)?.let {
                it.outcome.assert().isEqualTo(RuleOutcome.ERROR)
                it.error.assert().isSameAs(todo)
                it.afterDecision.assert().isTrue()
            }
        }
        val exhaustive = checkNotNull(evaluate(policies, TraceLevel.EXHAUSTIVE).trace).layers[1]
        exhaustive.groups.single().rules.single().error.assert().isSameAs(todo)
    }

    @Test
    fun `an error from a guard evaluated only for the trace is recorded`() {
        val todo = NotImplementedError()
        val erroring = TestGroup(
            "b",
            object : Guard {
                override fun matches(request: AuthzRequest): Boolean = throw todo
            },
            emptyList()
        )
        val policies = PolicySet(
            listOf(loaded(GLOBAL, fixture.policy("a", DENY to R.MATCH, guard = G.THROW), erroring))
        )
        val off = evaluate(policies, TraceLevel.OFF).decision
        off.assert().isInstanceOf(Decision.Indeterminate::class.java)
        val full = evaluate(policies, TraceLevel.FULL)
        full.decision.assert().isEqualTo(off)
        checkNotNull(full.trace).layers.single().groups[1].let {
            it.guard.assert().isEqualTo(GuardOutcome.ERROR)
            it.guardError.assert().isSameAs(todo)
        }
    }

    @Test
    fun `an error before the decision propagates at every level`() {
        val group = TestGroup("g", fixture.guard("g"), listOf(Grant(throwing("x", DENY) { NotImplementedError() })))
        TraceLevel.entries.forEach { level ->
            assertThrownBy<NotImplementedError> { evaluate(PolicySet(listOf(loaded(GLOBAL, group))), level) }
        }
        val guard = TestGroup(
            "g",
            object : Guard {
                override fun matches(request: AuthzRequest): Boolean = throw NotImplementedError()
            },
            emptyList()
        )
        TraceLevel.entries.forEach { level ->
            assertThrownBy<NotImplementedError> { evaluate(PolicySet(listOf(loaded(GLOBAL, guard))), level) }
        }
    }

    @Test
    fun `the JVM's own errors propagate even after the decision`() {
        val group = TestGroup(
            "g",
            fixture.guard("g"),
            listOf(Grant(fixture.rule("d", DENY)), Grant(throwing("x", DENY) { StackOverflowError() })),
        )
        val policies = PolicySet(listOf(loaded(GLOBAL, group)))
        evaluate(policies, TraceLevel.OFF).decision.assert().isInstanceOf(Decision.Deny::class.java)
        assertThrownBy<StackOverflowError> { evaluate(policies, TraceLevel.FULL) }
    }
}
