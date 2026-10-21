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

import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.Effect.ALLOW
import me.ahoo.cosec.model.Effect.DENY
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.LayerKind.GLOBAL
import me.ahoo.cosec.model.LayerKind.PRINCIPAL
import me.ahoo.cosec.model.LayerKind.ROLE
import me.ahoo.cosec.model.StatementRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import me.ahoo.cosec.engine.GuardBehavior as G
import me.ahoo.cosec.engine.RuleBehavior as R

/** The trace levels of RFC 0002 §5.1. */
class CoSecEngineTraceTest {
    private val fixture = Fixture()

    private fun stmt(layer: LayerKind, policy: String, index: Int) =
        StatementRef.PolicyStatement(layer, policy, index, "$policy.$index")

    private fun RuleTrace.summary() = "${(ref as? StatementRef.PolicyStatement)?.name ?: ref}:$outcome${if (afterDecision) "*" else ""}"

    private fun GroupTrace.summary() = "$id/$guard[${rules.joinToString(" ") { it.summary() }}]"

    private fun LayerTrace.summary() = "$kind:$state " + groups.joinToString(" ") { it.summary() }

    /** Global allows, principal denies and keeps going, role is after the decision. */
    private val policies = PolicySet(
        listOf(
            loaded(
                GLOBAL,
                fixture.policy("a", ALLOW to R.MATCH, ALLOW to R.THROW),
                fixture.policy("b", DENY to R.MATCH, guard = G.MISS),
            ),
            loaded(PRINCIPAL, fixture.policy("p", ALLOW to R.MATCH, DENY to R.MATCH, DENY to R.CONDITION_MISS)),
            loaded(ROLE, fixture.app("app", RoleGrant("admin", "delete", DENY, R.ACTION_MISS))),
        ),
    )

    @Test
    fun `off records nothing`() {
        CoSecEngine.evaluate(request(), policies, TraceLevel.OFF).trace.assert().isNull()
    }

    @Test
    fun `full records every rule of the layers the decision reaches`() {
        val evaluation = CoSecEngine.evaluate(request(), policies, TraceLevel.FULL)
        evaluation.decision.assert().isEqualTo(Decision.Deny(stmt(PRINCIPAL, "p", 1)))
        val trace = checkNotNull(evaluation.trace)
        trace.blocked.assert().isFalse()
        trace.root.assert().isFalse()
        trace.layers.map { it.summary() }.assert().containsExactly(
            // The layer's allow decided it; the allow that throws after it only shows in the trace.
            "GLOBAL:EVALUATED a/MATCHED[a.0:MATCHED a.1:ERROR*] b/MISSED[b.0:NOT_EVALUATED]",
            "PRINCIPAL:EVALUATED p/MATCHED[p.0:MATCHED* p.1:MATCHED p.2:CONDITION_MISS*]",
            "ROLE:NOT_EVALUATED ",
        )
        val global = trace.layers[0]
        global.matched.assert().isEqualTo(stmt(GLOBAL, "a", 0))
        global.matchedEffect.assert().isEqualTo(ALLOW)
        global.cause.assert().isNull()
        global.groups[0].rules[1].error.assert().isInstanceOf(MatcherException::class.java)
        trace.layers[1].matched.assert().isEqualTo(stmt(PRINCIPAL, "p", 1))
        trace.layers[1].matchedEffect.assert().isEqualTo(DENY)
        trace.layers[2].matched.assert().isNull()
    }

    @Test
    fun `exhaustive also evaluates the layers after the decision`() {
        val evaluation = CoSecEngine.evaluate(request(), policies, TraceLevel.EXHAUSTIVE)
        evaluation.decision.assert().isEqualTo(Decision.Deny(stmt(PRINCIPAL, "p", 1)))
        val role = checkNotNull(evaluation.trace).layers[2]
        role.state.assert().isEqualTo(LayerState.EVALUATED_AFTER_DECISION)
        role.groups.single().rules.single().let {
            it.ref.assert().isEqualTo(StatementRef.RolePermission("app", "admin", "delete", "admin.delete"))
            it.outcome.assert().isEqualTo(RuleOutcome.ACTION_MISS)
            it.afterDecision.assert().isTrue()
        }
    }

    @Test
    fun `a guard that throws is the layer's cause and the rest of the layer is after the decision`() {
        val throwing = fixture.policy("b", DENY to R.MATCH, guard = G.THROW)
        val layer =
            loaded(GLOBAL, fixture.policy("a", ALLOW to R.MATCH), throwing, fixture.policy("c", DENY to R.MATCH))
        val evaluation = CoSecEngine.evaluate(request(), PolicySet(listOf(layer)), TraceLevel.FULL)
        val cause = Cause.MatcherFailed(GLOBAL, "b", null, (throwing.guard as TestGuard).error)
        evaluation.decision.assert().isEqualTo(Decision.Indeterminate(cause))
        val trace = checkNotNull(evaluation.trace).layers.single()
        trace.summary().assert().isEqualTo(
            "GLOBAL:EVALUATED a/MATCHED[a.0:MATCHED*] b/ERROR[b.0:NOT_EVALUATED] c/MATCHED[c.0:MATCHED*]",
        )
        trace.groups[1].guardError.assert().isSameAs((throwing.guard as TestGuard).error)
        trace.cause.assert().isEqualTo(cause)
        // On its own, the layer would have denied.
        trace.matched.assert().isEqualTo(stmt(GLOBAL, "c", 0))
    }

    @Test
    fun `layers without groups record why`() {
        val missing = Cause.MissingPolicies(setOf("gone"), IllegalStateException("missing"))
        val unavailable = IllegalStateException("redis")
        val set = PolicySet(
            listOf(
                LayerInput.Absent(GLOBAL, AbsentReason.NO_POLICIES),
                LayerInput.Loaded(PRINCIPAL, emptyList(), missing),
                LayerInput.Unavailable(ROLE, unavailable),
            ),
        )
        val evaluation = CoSecEngine.evaluate(request(), set, TraceLevel.EXHAUSTIVE)
        evaluation.decision.assert().isEqualTo(Decision.Indeterminate(missing))
        val layers = checkNotNull(evaluation.trace).layers
        layers.map { it.state }.assert().containsExactly(
            LayerState.ABSENT,
            LayerState.MISSING_POLICIES,
            LayerState.UNAVAILABLE
        )
        layers[0].absentReason.assert().isEqualTo(AbsentReason.NO_POLICIES)
        layers[1].cause.assert().isEqualTo(missing)
        layers[2].cause.assert().isEqualTo(Cause.SourceUnavailable(ROLE, unavailable))
    }

    @Test
    fun `blacklist and root decide before every layer`() {
        val blocked = CoSecEngine.evaluate(request(blocked = true, root = true), policies, TraceLevel.FULL)
        blocked.decision.assert().isEqualTo(Decision.Deny(StatementRef.Blacklist))
        checkNotNull(blocked.trace).let { trace ->
            trace.blocked.assert().isTrue()
            trace.root.assert().isTrue()
            trace.layers.map { it.state }.assert().containsOnly(LayerState.NOT_EVALUATED)
        }

        val root = CoSecEngine.evaluate(request(root = true), policies, TraceLevel.EXHAUSTIVE)
        root.decision.assert().isEqualTo(Decision.Permit(StatementRef.Root))
        checkNotNull(root.trace).layers.map { it.state }.assert().containsOnly(LayerState.EVALUATED_AFTER_DECISION)
        checkNotNull(root.trace).layers.flatMap { it.groups }.flatMap { it.rules }
            .filter { it.outcome != RuleOutcome.NOT_EVALUATED }
            .assert().allMatch { it.afterDecision }

        CoSecEngine.evaluate(
            request(blocked = true),
            PolicySet.NOT_LOADED,
            TraceLevel.FULL
        ).trace!!.layers.assert().isEmpty()
    }

    @Test
    fun `an unresolved grant is recorded at its position`() {
        val app = fixture.app(
            "app",
            RoleGrant("viewer", "read", ALLOW),
            RoleGrant.unresolved("viewer", "gone"),
            RoleGrant("admin", "delete", DENY),
        )
        val evaluation = CoSecEngine.evaluate(request(), PolicySet(listOf(loaded(ROLE, app))), TraceLevel.FULL)
        val error = (app.grants[1].rule as UnresolvedGrant).error
        val cause = Cause.MissingPermissions(StatementRef.RolePermission("app", "viewer", "gone", ""), error)
        evaluation.decision.assert().isEqualTo(Decision.Indeterminate(cause))
        val layer = checkNotNull(evaluation.trace).layers.single()
        layer.cause.assert().isEqualTo(cause)
        layer.groups.single().rules.map { "${it.ref}:${it.effect}:${it.outcome}:${it.afterDecision}" }.assert().containsExactly(
            "RolePermission(appId=app, roleId=viewer, permissionId=read, name=viewer.read):ALLOW:MATCHED:true",
            "RolePermission(appId=app, roleId=viewer, permissionId=gone, name=):null:UNRESOLVED:false",
            "RolePermission(appId=app, roleId=admin, permissionId=delete, name=admin.delete):DENY:MATCHED:true",
        )
        layer.groups.single().rules[1].error.assert().isSameAs(error)
    }

    @Test
    fun `an unresolved grant after the deciding deny, or in a group whose condition missed, does not fail`() {
        val decided = fixture.app("app", RoleGrant("admin", "delete", DENY), RoleGrant.unresolved("viewer", "gone"))
        val evaluation = CoSecEngine.evaluate(request(), PolicySet(listOf(loaded(ROLE, decided))), TraceLevel.FULL)
        evaluation.decision.assert().isInstanceOf(Decision.Deny::class.java)
        checkNotNull(evaluation.trace).layers.single().groups.single().rules[1].let {
            it.outcome.assert().isEqualTo(RuleOutcome.UNRESOLVED)
            it.afterDecision.assert().isTrue()
        }

        val missed = fixture.app("app", RoleGrant.unresolved("viewer", "gone"), guard = G.MISS)
        val notReached = CoSecEngine.evaluate(request(), PolicySet(listOf(loaded(ROLE, missed))), TraceLevel.FULL)
        notReached.decision.assert().isEqualTo(Decision.Deny(null))
        checkNotNull(notReached.trace).layers.single().groups.single().rules.single().let {
            it.outcome.assert().isEqualTo(RuleOutcome.NOT_EVALUATED)
            it.effect.assert().isNull()
        }
    }

    @Test
    fun `traced evaluation of more than 64 groups`() {
        val groups = (0 until 70).map {
            fixture.policy("g$it", DENY to R.ACTION_MISS, guard = if (it % 2 == 0) G.MATCH else G.MISS)
        }
        val evaluation = CoSecEngine.evaluate(
            request(),
            PolicySet(listOf(LayerInput.Loaded(GLOBAL, groups))),
            TraceLevel.FULL
        )
        evaluation.decision.assert().isEqualTo(Decision.Deny(null))
        val traced = checkNotNull(evaluation.trace).layers.single().groups
        traced.size.assert().isEqualTo(70)
        traced.filter { it.guard == GuardOutcome.MATCHED }.flatMap { it.rules }.map { it.outcome }
            .assert().containsOnly(RuleOutcome.ACTION_MISS)
    }
}
