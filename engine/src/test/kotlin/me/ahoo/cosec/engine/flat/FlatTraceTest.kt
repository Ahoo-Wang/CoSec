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

import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class FlatTraceTest {
    private val fixture = FlatFixture()

    private fun trace(request: FlatRequest, view: SnapshotView, level: TraceLevel): FlatTrace =
        CoSecFlatEngine.evaluate(request, view, level).trace!!

    /** `<policy>.<index>:<outcome>[:after]` for each recorded statement. */
    private fun FlatTrace.lines(): List<String> =
        candidates.map { "${it.ref.name}:${it.outcome}" + if (it.afterDecision) ":after" else "" }

    @Test
    fun `a decision in the deny pass finishes it, and only EXHAUSTIVE evaluates the allows`() {
        val platform = platform(
            fixture.policy("a", deny(), allow()),
            fixture.policy("b", deny(RuleBehavior.THROW), allow(RuleBehavior.ACTION_MISS)),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b")),
        )
        val view = SnapshotView(platform, null, tenant())
        val full = trace(flatRequest(), view, TraceLevel.FULL)
        full.lines().assert().containsExactly(
            "blacklist:CONDITION_MISS",
            "a.0:MATCHED",
            "b.0:ERROR:after",
            "a.1:NOT_EVALUATED:after",
            "b.1:NOT_EVALUATED:after",
        )
        full.candidates[2].error.assert().isNotNull()
        full.candidates[1].via.assert().isEqualTo(bound("g1"))
        full.candidates[1].effect.assert().isEqualTo(Effect.DENY)
        trace(flatRequest(), view, TraceLevel.EXHAUSTIVE).lines().assert().containsExactly(
            "blacklist:CONDITION_MISS",
            "a.0:MATCHED",
            "b.0:ERROR:after",
            "a.1:MATCHED:after",
            "b.1:ACTION_MISS:after",
        )
    }

    @Test
    fun `a decision in the allow pass finishes it`() {
        val platform = platform(
            fixture.policy("a", allow()),
            fixture.policy("b", allow(RuleBehavior.CONDITION_MISS)),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b")),
        )
        trace(flatRequest(), SnapshotView(platform, null, tenant()), TraceLevel.FULL).lines().assert().containsExactly(
            "blacklist:CONDITION_MISS",
            "a.0:MATCHED",
            "b.0:CONDITION_MISS:after",
        )
    }

    @Test
    fun `conditions are recorded per statement`() {
        val platform = platform(
            fixture.policy("a", deny(), allow(), guard = fixture.guard("miss", GuardBehavior.MISS)),
            fixture.policy("b", deny(), guard = fixture.guard("throw", GuardBehavior.THROW)),
            fixture.policy("c", allow(), guard = fixture.guard("match")),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b"), anyone("g3", "c")),
        )
        val trace = trace(flatRequest(), SnapshotView(platform, null, tenant()), TraceLevel.EXHAUSTIVE)
        trace.lines().assert().containsExactly(
            "blacklist:CONDITION_MISS",
            "a.0:GUARD_MISS",
            "b.0:GUARD_ERROR",
            "a.1:GUARD_MISS:after",
            "c.0:MATCHED:after",
        )
        trace.candidates[2].error.assert().isNotNull()
    }

    /** A policy whose one DENY statement reads the probe [probe], and whose condition reads [guard] if given. */
    private fun probed(id: String, probe: String, guard: String? = null): CompiledPolicy =
        PolicyCompiler().compile(
            PolicyId(id),
            guard?.let { Condition.Eq("request.attributes.$it", Literal.Str("yes")) },
            listOf(
                StatementSource(
                    "$id.0",
                    Effect.DENY,
                    listOf("*"),
                    condition = Condition.Eq("request.attributes.$probe", Literal.Str("yes")),
                ),
            ),
        )

    @Test
    fun `failures after the decision are recorded, not thrown`() {
        Probes.register("trace-after", Probes.Probe(fixture.log, "after") { throw AssertionError("after") })
        Probes.register("trace-yes", Probes.Probe(fixture.log, "yes") { "yes" })
        val platform = platform(
            fixture.policy("a", deny()),
            probed("b", "trace-after"),
            probed("c", "trace-yes", guard = "trace-after"),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b"), anyone("g3", "c")),
        )
        val evaluation = CoSecFlatEngine.evaluate(
            flatRequest(),
            SnapshotView(platform, null, tenant()),
            TraceLevel.FULL
        )
        evaluation.decision.assert().isEqualTo(Decision.Deny(ref("a", 0, bound("g1"))))
        evaluation.trace!!.lines().assert().containsExactly(
            "blacklist:CONDITION_MISS",
            "a.0:MATCHED",
            "b.0:ERROR:after",
            "c.0:GUARD_ERROR:after",
        )
    }

    @Test
    fun `an interruption after the decision still propagates`() {
        Probes.register("trace-interrupted", Probes.Probe(fixture.log, "interrupted") { throw InterruptedException() })
        val platform = platform(
            fixture.policy("a", deny()),
            probed("b", "trace-interrupted"),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b")),
        )
        assertThrownBy<InterruptedException> {
            CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, tenant()), TraceLevel.FULL)
        }
        // Untraced, the evaluation stops at the decision, before it.
        CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, tenant())).decision.assert()
            .isEqualTo(Decision.Deny(ref("a", 0, bound("g1"))))
    }

    @Test
    fun `statements the index leaves out are recorded by what ruled them out`() {
        fun statement(name: String, action: String, resource: String?, condition: Condition? = null) =
            StatementSource(name, Effect.ALLOW, listOf(action), listOfNotNull(resource?.let(::ResourceSource)), condition)
        val policy = PolicyCompiler(options = IndexOptions(minStatements = 1)).compile(
            PolicyId("p"),
            null,
            listOf(
                statement("action", "order:read", null),
                statement("resource", "*", "http:path:/users/**"),
                statement("keys", "*", null, Condition.Eq("scope.app", Literal.Str("other"))),
                statement("match", "http:GET", "http:path:/orders/{id}"),
            ),
        )
        val trace = trace(
            flatRequest(),
            SnapshotView(platform(policy, bindings = listOf(anyone("g", "p"))), null, tenant()),
            TraceLevel.FULL,
        )
        trace.lines().assert().containsExactly(
            "blacklist:CONDITION_MISS",
            "action:ACTION_MISS",
            "resource:RESOURCE_MISS",
            "keys:CONDITION_MISS",
            "match:MATCHED",
        )
    }

    @Test
    fun `expired bindings and what is unresolved are recorded`() {
        val platform = platform(
            fixture.policy("a", allow()),
            bindings = listOf(anyone("old", "a", expiresAt = NOW), anyone("new", "a", expiresAt = NOW + 1)),
        )
        val cause = Cause.SourceUnavailable(DocumentKind.APP, IllegalStateException("redis"))
        val trace = trace(
            flatRequest(attachments = listOf("ghost")),
            SnapshotView(platform, null, null, listOf(cause)),
            TraceLevel.FULL,
        )
        trace.expired.assert().containsExactly(bound("old"))
        trace.unresolved.assert().containsExactly(
            cause,
            Cause.NotLoaded(DocumentKind.TENANT),
            Cause.MissingPolicies(setOf(PolicyId("ghost"))),
        )
        trace.candidates.map { it.via }.distinct().assert().containsExactly(BindingRef.Builtin, bound("new"))
    }
}
