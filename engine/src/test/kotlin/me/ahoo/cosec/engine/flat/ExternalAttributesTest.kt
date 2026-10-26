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

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** RFC 0006 §5.4.2, §5.4.3: external attributes, `onMissing`, and the attribute plan. */
class ExternalAttributesTest {
    private val inFence = Attribute.external("ext.geo.inFence", AttributeType.BOOL)
    private val country = Attribute.external("ext.geo.country", AttributeType.STRING)
    private val compiler = PolicyCompiler(ConditionChecker(catalog = AttributeCatalog.of(listOf(inFence, country))))

    private fun inFence(value: Boolean) = Condition.Eq("ext.geo.inFence", Literal.Bool(value))

    private fun policy(id: String, vararg statements: StatementSource, condition: Condition? = null) =
        compiler.compile(PolicyId(id), condition, statements.toList(), denyNomatch = true)

    private fun statement(effect: Effect, condition: Condition?, onMissing: OnMissing? = null, action: String = "*") =
        StatementSource("s", effect, listOf(action), condition = condition, onMissing = onMissing)

    private fun view(vararg policies: CompiledPolicy): SnapshotView = SnapshotView(
        platform(*policies, bindings = policies.map { anyone("b:${it.policyId.value}", it.policyId.value) }),
        null,
        tenant(),
    )

    private fun decide(view: SnapshotView, external: ExternalValues, level: TraceLevel = TraceLevel.OFF): Decision {
        val base = flatRequest()
        val request = FlatRequest(base.request, base.scope, base.memberships, base.time, base.attributes, external)
        return CoSecFlatEngine.evaluate(request, view, level).decision
    }

    /** Every level decides alike (P2), so each case is checked at all of them. */
    private fun decision(view: SnapshotView, external: ExternalValues): Decision {
        val decisions = TraceLevel.entries.map { decide(view, external, it) }
        decisions.distinct().assert().describedAs("decisions by trace level: $decisions").hasSize(1)
        return decisions.first()
    }

    private val missing = ExternalValues.NONE
    private fun present(value: Boolean) = ExternalValues(mapOf(inFence.path to value), setOf(inFence.path))

    @Test
    fun aDenyMissingItsAttributeIsIndeterminate() {
        val view = view(policy("d", statement(Effect.DENY, inFence(false))), policy("a", statement(Effect.ALLOW, null)))
        val decision = decision(view, missing) as Decision.Indeterminate
        val cause = decision.cause as Cause.AttributeUnavailable
        cause.attributes.assert().containsExactly("ext.geo.inFence")
        cause.at!!.policy.value.assert().isEqualTo("d")
        decision(view, present(true)).assert().isInstanceOf(Decision.Permit::class.java)
        decision(view, present(false)).assert().isInstanceOf(Decision.Deny::class.java)
    }

    @Test
    fun anotherMatchingDenyStillDenies() {
        val view = view(policy("d", statement(Effect.DENY, inFence(false))), policy("d2", statement(Effect.DENY, null)))
        (decision(view, missing) as Decision.Deny).by.assert().isNotNull()
    }

    @Test
    fun aDenyWithNomatchLapses() {
        val view = view(
            policy("d", statement(Effect.DENY, inFence(false), OnMissing.NOMATCH)),
            policy("a", statement(Effect.ALLOW, null)),
        )
        decision(view, missing).assert().isInstanceOf(Decision.Permit::class.java)
    }

    /** K12: an ALLOW skipped for a missing attribute turns the implicit deny into a 503, never into a Permit. */
    @Test
    fun aSkippedAllowIsIndeterminateWhenNothingPermits() {
        val view = view(policy("a", statement(Effect.ALLOW, inFence(true))))
        val cause = (decision(view, missing) as Decision.Indeterminate).cause as Cause.AttributeUnavailable
        cause.at!!.policy.value.assert().isEqualTo("a")
        decision(view, present(true)).assert().isInstanceOf(Decision.Permit::class.java)
        decision(view, present(false)).assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun anotherAllowStillPermits() {
        val view = view(
            policy("a", statement(Effect.ALLOW, inFence(true))),
            policy("a2", statement(Effect.ALLOW, null))
        )
        decision(view, missing).assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun anAllowWithNomatchIsNoMatch() {
        val view = view(policy("a", statement(Effect.ALLOW, inFence(true), OnMissing.NOMATCH)))
        decision(view, missing).assert().isEqualTo(Decision.Deny(null))
    }

    /** An absent attribute is not a missing one (RFC 0005 §3.4): the comparison is false. */
    @Test
    fun absentIsNotMissing() {
        val view = view(policy("a", statement(Effect.ALLOW, inFence(true))))
        decision(view, ExternalValues(emptyMap(), setOf(inFence.path))).assert().isEqualTo(Decision.Deny(null))
        ExternalValues(emptyMap(), setOf(inFence.path)).status(inFence.path).assert().isEqualTo(AttributeStatus.ABSENT)
        present(true).status(inFence.path).assert().isEqualTo(AttributeStatus.PRESENT)
        missing.status(inFence.path).assert().isEqualTo(AttributeStatus.UNAVAILABLE)
    }

    /** A value of another type is missing, never absent: a DENY on it must not read as a miss. */
    @Test
    fun aValueOfTheWrongTypeIsMissing() {
        val view = view(policy("d", statement(Effect.DENY, inFence(false))), policy("a", statement(Effect.ALLOW, null)))
        val wrong = ExternalValues(mapOf(inFence.path to "false"), setOf(inFence.path))
        decision(view, wrong).assert().isInstanceOf(Decision.Indeterminate::class.java)
    }

    /** A missing attribute in a policy condition follows the statement reached through it. */
    @Test
    fun aMissingPolicyConditionFollowsTheStatement() {
        val guarded = policy("d", statement(Effect.DENY, null), condition = inFence(false))
        val view = view(guarded, policy("a", statement(Effect.ALLOW, null)))
        decision(view, missing).assert().isInstanceOf(Decision.Indeterminate::class.java)
        decision(view, present(true)).assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun theUnresolvedPolicyConditionWithNomatchLeavesTheOtherStatements() {
        val guarded = policy(
            "d",
            statement(Effect.DENY, inFence(false), OnMissing.NOMATCH),
            statement(Effect.DENY, Condition.Eq("ext.geo.country", Literal.Str("x"))),
            condition = inFence(false),
        )
        val view = view(guarded, policy("a", statement(Effect.ALLOW, null)))
        // The second DENY treats the missing policy condition as an error, so nothing permits.
        decision(view, missing).assert().isInstanceOf(Decision.Indeterminate::class.java)
    }

    @Test
    fun thePlanHoldsTheCandidatesExternalAttributes() {
        val base = flatRequest()
        val none = view(policy("p", statement(Effect.ALLOW, null)))
        CoSecFlatEngine.plan(base, none).assert().isSameAs(AttributePlan.EMPTY)

        val view = view(
            policy("d", statement(Effect.DENY, inFence(false))),
            policy(
                "other",
                statement(Effect.ALLOW, Condition.Eq("ext.geo.country", Literal.Str("x")), action = "svc:verb")
            ),
            policy("g", statement(Effect.ALLOW, null), condition = Condition.Eq("ext.geo.country", Literal.Str("x"))),
        )
        val plan = CoSecFlatEngine.plan(base, view)
        plan.attributes.assert().containsExactlyInAnyOrder(inFence, country)
        plan.isEmpty.assert().isFalse()
        plan.toString().assert().contains("ext.geo.inFence")

        // A statement whose action misses is no candidate.
        val notACandidate = view(
            policy(
                "other",
                statement(Effect.ALLOW, Condition.Eq("ext.geo.country", Literal.Str("x")), action = "svc:verb")
            ),
        )
        CoSecFlatEngine.plan(base, notACandidate).isEmpty.assert().isTrue()
    }

    @Test
    fun onMissingNeedsAnExternalAttribute() {
        val error = assertThrows<CompileException> {
            compiler.compile(PolicyId("p"), null, listOf(statement(Effect.ALLOW, null, OnMissing.NOMATCH)))
        }
        error.errors.single().path.assert().isEqualTo("$.statements[0].onMissing")
    }

    /** RFC 0006 Q13: only the platform document may let a DENY lapse while its provider fails. */
    @Test
    fun aDenyMayLapseOnlyWhereAllowed() {
        val lapsing = statement(Effect.DENY, inFence(false), OnMissing.NOMATCH)
        assertThrows<CompileException> { compiler.compile(PolicyId("p"), null, listOf(lapsing)) }
            .errors.single().message.assert().contains("platform document")
        compiler.compile(PolicyId("p"), null, listOf(lapsing), denyNomatch = true).statements.single().onMissing
            .assert().isEqualTo(OnMissing.NOMATCH)
        compiler.compile(PolicyId("p"), null, listOf(statement(Effect.ALLOW, inFence(true), OnMissing.NOMATCH)))
            .statements.single().onMissing.assert().isEqualTo(OnMissing.NOMATCH)
    }

    @Test
    fun onMissingIsPartOfWhatAStatementMeans() {
        val a = compiler.statement(PolicyId("p"), 0, statement(Effect.ALLOW, inFence(true)))
        val b = compiler.statement(PolicyId("p"), 0, statement(Effect.ALLOW, inFence(true), OnMissing.NOMATCH))
        a.content.assert().isNotEqualTo(b.content)
    }
}
