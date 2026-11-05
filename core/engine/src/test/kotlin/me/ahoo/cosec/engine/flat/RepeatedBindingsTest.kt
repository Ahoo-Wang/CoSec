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

import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `ALG-9` (review M1 of #1200): a pass evaluates a policy once for all the bindings of one document that reach it, and
 * an attachment claimed twice once. Bindings cost nothing more than the first, so DOC-4's budget bounds a request:
 * each statement's condition is a probe that counts its evaluations.
 */
class RepeatedBindingsTest {
    private val fixture = FlatFixture()

    /** How often each statement's condition ran, untraced. */
    private fun evaluations(request: FlatRequest, view: SnapshotView): Map<String, Int> {
        fixture.log.clear()
        CoSecFlatEngine.evaluate(request, view)
        return fixture.log.calls.groupingBy { it }.eachCount()
    }

    private fun missing() = fixture.policy("p", deny(RuleBehavior.CONDITION_MISS), allow(RuleBehavior.CONDITION_MISS))

    @Test
    fun `many bindings of one document cost one evaluation per pass`() {
        val platform = platform(
            missing(),
            bindings = (1..64).map { anyone("g$it", "p") } + (1..8).map { principal("u$it", "p") },
        )
        evaluations(flatRequest(), SnapshotView(platform, null, tenant()))
            .assert().isEqualTo(mapOf("rule:p.0" to 1, "rule:p.1" to 1))
    }

    @Test
    fun `role bindings across the principal's roles cost one evaluation`() {
        val roles = (1..8).map { "r$it" }
        val app = app(missing(), bindings = roles.map { role("b-$it", it, "p") })
        evaluations(flatRequest(roles = roles), SnapshotView(platform(), app, tenant()))
            .assert().isEqualTo(mapOf("rule:p.0" to 1, "rule:p.1" to 1))
    }

    @Test
    fun `a platform policy costs at most one evaluation per document that binds it`() {
        val platform = platform(missing(), bindings = listOf(anyone("g", "p")))
        val tenant = tenant(
            bindings = (1..16).map { anyone("t$it", "p") } + (1..4).map { role("tr$it", "r", "p", tenant = "acme") },
        )
        evaluations(flatRequest(roles = listOf("r")), SnapshotView(platform, app(), tenant))
            .assert().isEqualTo(mapOf("rule:p.0" to 2, "rule:p.1" to 2))
    }

    @Test
    fun `tenant bindings of an app permission cost one evaluation`() {
        val app = app(missing(), bindings = listOf(role("b", "r", "p")))
        val tenant = tenant(bindings = (1..16).map { anyone("t$it", "p", app = "app") })
        evaluations(flatRequest(roles = listOf("r")), SnapshotView(platform(), app, tenant))
            .assert().isEqualTo(mapOf("rule:p.0" to 2, "rule:p.1" to 2))
    }

    @Test
    fun `an attachment claimed many times costs one evaluation`() {
        val attached = fixture.policy(
            "p",
            deny(RuleBehavior.CONDITION_MISS),
            allow(RuleBehavior.CONDITION_MISS),
            attachable = true,
        )
        // More than the pairwise comparison handles, besides: attached policies whose action never matches.
        val others = (1..32).map { fixture.policy("x$it", deny(RuleBehavior.ACTION_MISS), attachable = true) }
        val view = SnapshotView(platform(attached, *others.toTypedArray()), null, tenant())
        evaluations(flatRequest(attachments = List(32) { "p" }), view)
            .assert().isEqualTo(mapOf("rule:p.0" to 1, "rule:p.1" to 1))
        evaluations(flatRequest(attachments = List(64) { "p" } + others.map { it.id }), view)
            .assert().isEqualTo(mapOf("rule:p.0" to 1, "rule:p.1" to 1))
    }

    @Test
    fun `the first binding in candidate order reports the match`() {
        val platform = platform(
            fixture.policy("p", deny()),
            bindings = listOf(anyone("g2", "p"), anyone("g1", "p"), anyone("g3", "p")),
        )
        val view = SnapshotView(platform, null, tenant())
        for (level in TraceLevel.entries) {
            CoSecFlatEngine.evaluate(flatRequest(), view, level).decision.assert()
                .isEqualTo(Decision.Deny(ref("p", 0, bound("g1"))))
        }
    }

    @Test
    fun `the first binding in candidate order records the error`() {
        val failing = fixture.policy("p", deny(RuleBehavior.THROW))
        val platform = platform(failing, bindings = listOf(anyone("g1", "p"), anyone("g2", "p")))
        val error = fixture.error(failing, "p.0")
        for (level in TraceLevel.entries) {
            CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, tenant()), level).decision.assert()
                .isEqualTo(Decision.Indeterminate(Cause.MatcherFailed(ref("p", 0, bound("g1")), false, error)))
        }
    }

    @Test
    fun `a binding that does not apply leaves the policy to the next one`() {
        val platform = platform(
            fixture.policy("p", deny()),
            bindings = listOf(
                anyone("g1", "p", expiresAt = NOW - 1),
                anyone("g2", "p", app = "other"),
                anyone("g3", "p"),
                anyone("g4", "p"),
            ),
        )
        CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, tenant())).decision.assert()
            .isEqualTo(Decision.Deny(ref("p", 0, bound("g3"))))
    }

    @Test
    fun `each pass evaluates a repeated policy once`() {
        val platform = platform(
            fixture.policy("p", deny(RuleBehavior.CONDITION_MISS), allow()),
            bindings = listOf(anyone("g1", "p"), anyone("g2", "p")),
        )
        val view = SnapshotView(platform, null, tenant())
        evaluations(flatRequest(), view).assert().isEqualTo(mapOf("rule:p.0" to 1, "rule:p.1" to 1))
        CoSecFlatEngine.evaluate(flatRequest(), view).decision.assert()
            .isEqualTo(Decision.Permit(ref("p", 1, bound("g1"))))
    }

    @Test
    fun `the trace lists the later bindings with the first one's outcomes`() {
        val platform = platform(
            fixture.policy("p", deny(RuleBehavior.CONDITION_MISS), allow()),
            bindings = listOf(anyone("g1", "p"), anyone("g2", "p")),
        )
        val view = SnapshotView(platform, null, tenant())
        fixture.log.clear()
        val trace = CoSecFlatEngine.evaluate(flatRequest(), view, TraceLevel.FULL).trace!!
        fixture.log.calls.assert().containsExactly("rule:p.0", "rule:p.1")
        val lines = trace.candidates.drop(1).map {
            "${it.ref.name}:${(it.via as BindingRef.Bound).id.value}:${it.outcome}:${it.afterDecision}"
        }
        lines.assert().containsExactly(
            "p.0:g1:CONDITION_MISS:false",
            "p.0:g2:CONDITION_MISS:false",
            "p.1:g1:MATCHED:false",
            "p.1:g2:MATCHED:true",
        )
    }

    @Test
    fun `a document that binds each policy once has no repeated targets`() {
        platform(
            fixture.policy("a", deny()),
            fixture.policy("b", deny()),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b")),
        ).repeats.assert().isZero()
        tenant(bindings = listOf(anyone("t1", "p"), anyone("t2", "q"))).repeats.assert().isZero()
        val bindings = listOf(role("b1", "r1", "p"), role("b2", "r2", "p"), role("b3", "r3", "q"))
        app(fixture.policy("p", allow()), bindings = bindings).repeats.assert().isEqualTo(1)
    }
}
