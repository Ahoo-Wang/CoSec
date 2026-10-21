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
    // Round 2: what only the trace looks at never changes the decision, and nulls from Java adapters fail closed.

    /** A null where Kotlin says there is none, as a Java adapter may return. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> javaNull(): T = null as T

    private fun decisions(policies: PolicySet) = TraceLevel.entries.map { evaluate(policies, it).decision }

    private fun contractErrors(policies: PolicySet, level: TraceLevel) =
        checkNotNull(evaluate(policies, level).trace).layers.flatMap { it.groups }.mapNotNull { it.contractError }

    private val permitted = Decision.Permit(StatementRef.PolicyStatement(GLOBAL, "g", 0, "g.0"))

    @Test
    fun `a broken entry in a group whose guard missed is recorded, not thrown (scenario A)`() {
        val missed = TestGroup(
            "app2",
            fixture.guard("app2", G.MISS),
            listOf(Grant(fixture.rule("d", DENY, id = null), "admin")),
        )
        val policies = PolicySet(listOf(loaded(GLOBAL, fixture.policy("g", ALLOW to R.MATCH)), loaded(ROLE, missed)))
        decisions(policies).assert().containsOnly(permitted)
        contractErrors(policies, TraceLevel.FULL).single().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a group whose guard missed and whose forEachRule throws is recorded, not thrown (scenario B)`() {
        val failure = IllegalStateException("adapter")
        val missed = object : RuleGroup {
            override val id = "app2"
            override val guard: Guard = fixture.guard("app2", G.MISS)
            override fun forEachRule(visitor: RuleVisitor): Boolean = throw failure
        }
        val policies = PolicySet(listOf(loaded(GLOBAL, fixture.policy("g", ALLOW to R.MATCH)), loaded(ROLE, missed)))
        decisions(policies).assert().containsOnly(permitted)
        contractErrors(policies, TraceLevel.EXHAUSTIVE).assert().containsExactly(failure)
    }

    @Test
    fun `a null match fails the evaluation at every level (scenario D)`() {
        val nullMatch = object : Rule by fixture.rule("n", DENY) {
            override fun match(request: AuthzRequest): RuleMatch = javaNull()
        }
        val group = TestGroup("g", fixture.guard("g"), listOf(Grant(nullMatch), Grant(fixture.rule("a", ALLOW))))
        TraceLevel.entries.forEach { level ->
            assertThrownBy<IllegalStateException> { evaluate(PolicySet(listOf(loaded(GLOBAL, group))), level) }
        }
    }

    @Test
    fun `a null effect fails the evaluation at every level`() {
        val noEffect = object : Rule by fixture.rule("n", DENY) {
            override val effect: Effect get() = javaNull()
        }
        val group = TestGroup("g", fixture.guard("g"), listOf(Grant(noEffect), Grant(fixture.rule("a", ALLOW))))
        TraceLevel.entries.forEach { level ->
            assertThrownBy<IllegalStateException> { evaluate(PolicySet(listOf(loaded(GLOBAL, group))), level) }
        }
    }

    @Test
    fun `a null match or broken entry after the decision is recorded, not thrown`() {
        val nullMatch = object : Rule by fixture.rule("n", DENY) {
            override fun match(request: AuthzRequest): RuleMatch = javaNull()
        }
        val policy = TestGroup("g", fixture.guard("g"), listOf(Grant(fixture.rule("d", DENY)), Grant(nullMatch)))
        val app = TestGroup("app", fixture.guard("app"), listOf(Grant(fixture.rule("b", DENY, id = null), "admin")))
        val policies = PolicySet(listOf(loaded(GLOBAL, policy), loaded(ROLE, app)))
        decisions(policies).assert().containsOnly(Decision.Deny(StatementRef.PolicyStatement(GLOBAL, "g", 0, "d")))
        contractErrors(policies, TraceLevel.FULL).size.assert().isEqualTo(1)
        // EXHAUSTIVE also lists the role layer after the decision.
        contractErrors(policies, TraceLevel.EXHAUSTIVE).size.assert().isEqualTo(2)
    }

    /** Shows an ALLOW only when its visitor is in the allow pass. */
    private class ShiftingGroup(
        override val id: String,
        override val guard: Guard,
        private val allow: Rule
    ) : RuleGroup {
        private var calls = 0

        override fun forEachRule(visitor: RuleVisitor): Boolean =
            if (calls++ % 2 == 1) visitor.visit(allow, null) else false
    }

    @Test
    fun `an allow pass that visits other entries than the deny pass is recorded, not thrown (scenario F)`() {
        val policies = {
            PolicySet(
                listOf(loaded(GLOBAL, ShiftingGroup("g", fixture.guard("g"), fixture.rule("g.0", ALLOW))))
            )
        }
        TraceLevel.entries.map { evaluate(policies(), it).decision }.assert().containsOnly(permitted)
        contractErrors(policies(), TraceLevel.FULL).single().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `an interruption after the decision propagates, and before it fails the layer as untraced`() {
        val interrupted = InterruptedException()
        val after = TestGroup(
            "g",
            fixture.guard(
                "g"
            ),
            listOf(
                Grant(fixture.rule("d", DENY)),
                Grant(
                    throwing("x", DENY) {
                        interrupted
                    }
                )
            )
        )
        val policies = PolicySet(listOf(loaded(GLOBAL, after)))
        evaluate(policies, TraceLevel.OFF).decision.assert().isInstanceOf(Decision.Deny::class.java)
        assertThrownBy<InterruptedException> { evaluate(policies, TraceLevel.FULL) }

        val guardAfter = TestGroup(
            "b",
            object : Guard {
                override fun matches(request: AuthzRequest): Boolean = throw interrupted
            },
            emptyList()
        )
        val guards = PolicySet(
            listOf(loaded(GLOBAL, fixture.policy("a", DENY to R.MATCH, guard = G.THROW), guardAfter))
        )
        evaluate(guards, TraceLevel.OFF).decision.assert().isInstanceOf(Decision.Indeterminate::class.java)
        assertThrownBy<InterruptedException> { evaluate(guards, TraceLevel.FULL) }

        val before = TestGroup("g", fixture.guard("g"), listOf(Grant(throwing("x", DENY) { interrupted })))
        TraceLevel.entries.forEach { level ->
            evaluate(PolicySet(listOf(loaded(GLOBAL, before))), level).decision
                .assert().isInstanceOf(Decision.Indeterminate::class.java)
        }
    }

    @Test
    fun `a JVM error from a guard after the decision propagates (M22)`() {
        val overflowing = TestGroup(
            "b",
            object : Guard {
                override fun matches(request: AuthzRequest): Boolean = throw StackOverflowError()
            },
            emptyList()
        )
        val policies = PolicySet(
            listOf(loaded(GLOBAL, fixture.policy("a", DENY to R.MATCH, guard = G.THROW), overflowing))
        )
        evaluate(policies, TraceLevel.OFF).decision.assert().isInstanceOf(Decision.Indeterminate::class.java)
        assertThrownBy<StackOverflowError> { evaluate(policies, TraceLevel.FULL) }
    }

    @Test
    fun `a linkage error after the decision propagates (M23)`() {
        val group = TestGroup(
            "g",
            fixture.guard("g"),
            listOf(Grant(fixture.rule("d", DENY)), Grant(throwing("x", DENY) { NoClassDefFoundError("gone") })),
        )
        val policies = PolicySet(listOf(loaded(GLOBAL, group)))
        evaluate(policies, TraceLevel.OFF).decision.assert().isInstanceOf(Decision.Deny::class.java)
        assertThrownBy<NoClassDefFoundError> { evaluate(policies, TraceLevel.FULL) }
    }

    @Test
    fun `a layer's trace keeps its first matched deny and first matched allow (M30, M31)`() {
        val denies = PolicySet(listOf(loaded(GLOBAL, fixture.policy("d", DENY to R.MATCH, DENY to R.MATCH))))
        checkNotNull(evaluate(denies, TraceLevel.FULL).trace).layers.single().matched
            .assert().isEqualTo(StatementRef.PolicyStatement(GLOBAL, "d", 0, "d.0"))
        val allows = PolicySet(listOf(loaded(GLOBAL, fixture.policy("a", ALLOW to R.MATCH, ALLOW to R.MATCH))))
        checkNotNull(evaluate(allows, TraceLevel.FULL).trace).layers.single().matched
            .assert().isEqualTo(StatementRef.PolicyStatement(GLOBAL, "a", 0, "a.0"))
    }
}
