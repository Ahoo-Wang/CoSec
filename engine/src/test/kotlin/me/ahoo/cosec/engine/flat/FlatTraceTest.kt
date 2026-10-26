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

import me.ahoo.cosec.engine.Guard
import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
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

    @Test
    fun `failures after the decision are recorded, not thrown`() {
        val failing = object : Rule {
            override val effect = Effect.DENY
            override val index = 0
            override val name = "oom"
            override val id: String? = null

            override fun match(request: AuthzRequest): RuleMatch = throw AssertionError("after")
        }
        val failingGuard = object : Guard {
            override fun matches(request: AuthzRequest): Boolean = throw AssertionError("guard")
        }
        val platform = platform(
            fixture.policy("a", deny()),
            CompiledPolicy(PolicyId("b"), null, listOf(failing)),
            CompiledPolicy(PolicyId("c"), failingGuard, listOf(failing)),
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
            "oom:ERROR:after",
            "oom:GUARD_ERROR:after",
        )
    }

    @Test
    fun `an interruption after the decision still propagates`() {
        val interrupted = object : Rule {
            override val effect = Effect.DENY
            override val index = 0
            override val name = "interrupted"
            override val id: String? = null

            override fun match(request: AuthzRequest): RuleMatch = throw InterruptedException()
        }
        val platform = platform(
            fixture.policy("a", deny()),
            CompiledPolicy(PolicyId("b"), null, listOf(interrupted)),
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
    fun `a broken statement is recorded as an error`() {
        val broken = object : Rule {
            override val effect = Effect.ALLOW
            override val index = 0
            override val name = "broken"
            override val id: String? = null

            override fun match(request: AuthzRequest): RuleMatch = javaNull()
        }
        val brokenDeny = object : Rule by broken {
            override val effect = Effect.DENY
        }
        val platform = platform(
            CompiledPolicy(PolicyId("b"), null, listOf(broken, brokenDeny)),
            bindings = listOf(anyone("g", "b")),
        )
        val evaluation = CoSecFlatEngine.evaluate(
            flatRequest(),
            SnapshotView(platform, null, tenant()),
            TraceLevel.FULL
        )
        evaluation.decision.assert().isInstanceOf(Decision.Indeterminate::class.java)
        evaluation.trace!!.lines().assert().containsExactly(
            "blacklist:CONDITION_MISS",
            "broken:ERROR",
            "broken:NOT_EVALUATED:after",
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

    /** What a Java matcher returns when it breaks its contract. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> javaNull(): T = null as T
}
