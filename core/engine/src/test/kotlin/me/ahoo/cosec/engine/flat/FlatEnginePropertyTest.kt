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
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The properties of RFC 0004 §9.5 that the engine owns (P1′, P8, P9, P10, P12) and P2 of RFC 0002, on random snapshots
 * with a fixed seed ([FlatGenerator]), plus a check of the decision against [Oracle], a direct transcription of §4.2,
 * §6.1 and §6.3 over the raw bindings.
 */
class FlatEnginePropertyTest {
    private val iterations = 3_000

    private fun forEachCase(seed: Int, block: (FlatCase, Random) -> Unit) {
        val random = Random(seed)
        repeat(iterations) { iteration ->
            val case = FlatGenerator(random).case()
            try {
                block(case, random)
            } catch (e: AssertionError) {
                throw AssertionError("Case $iteration (seed $seed):\n${case.describe()}", e)
            }
        }
    }

    @Test
    fun `the decision is that of the pseudocode`() {
        val kinds = mutableSetOf<Class<*>>()
        forEachCase(seed = 20261026) { case, _ ->
            val decision = case.evaluate().decision
            decision.assert().isEqualTo(Oracle.decide(case))
            kinds += decision.javaClass
        }
        // The generator reaches every kind of decision.
        kinds.assert().hasSize(3)
    }

    /** P2: the decision does not depend on the trace level. */
    @Test
    fun `P2 the decision does not depend on the trace level`() {
        forEachCase(seed = 2) { case, _ ->
            val untraced = case.evaluate().decision
            for (level in listOf(TraceLevel.FULL, TraceLevel.EXHAUSTIVE)) {
                case.evaluate(level).decision.assert().describedAs("$level").isEqualTo(untraced)
            }
        }
    }

    /** P10: the index finds what a scan of every binding finds, in the candidate order. */
    @Test
    fun `P10 the binding index agrees with a scan of every binding`() {
        forEachCase(seed = 10) { case, _ ->
            for (effect in Effect.entries) {
                val expected = Oracle.enumerate(case, effect)
                val collector = Collector()
                CandidateOrder.forEach(case.request, case.view(), effect, collector)
                collector.candidates.assert().describedAs("$effect").isEqualTo(expected.candidates)
                collector.expired.assert().describedAs("$effect").isEqualTo(expected.expired)
                collector.missing.assert().describedAs("$effect").isEqualTo(expected.missing)
                collector.ambiguous.assert().describedAs("$effect").isEqualTo(expected.ambiguous)
            }
        }
    }

    /** P1′: adding a DENY that applies and matches always denies, root and unresolved data included. */
    @Test
    fun `P1' adding a matching deny always denies`() {
        forEachCase(seed = 1) { case, random ->
            val augmented = case.withMatchingDeny(random)
            for (level in TraceLevel.entries) {
                val decision = augmented.evaluate(level).decision
                decision.assert().describedAs("$level: $decision").isInstanceOf(Decision.Deny::class.java)
                (decision as Decision.Deny).by.assert().isNotNull()
            }
        }
    }

    /**
     * P1′ through an id both layers define (RFC 0007 N2): when the tenant defines the id of an attached platform DENY,
     * the tenant's copy never replaces it. No permit, and the id is reported as ambiguous.
     */
    @Test
    fun `P1' a tenant policy of the same id never cancels an attached deny`() {
        forEachCase(seed = 11) { case, random ->
            if (!case.request.request.subject.authenticated) return@forEachCase
            val deny = case.fixture.policy("added-deny", deny(), attachable = true)
            val copy = case.fixture.policy("added-deny", allow(), attachable = random.nextBoolean())
            val shadowed = case.copy(
                platform = case.platform.with(deny),
                tenant = case.tenant.with(copy),
                tenantLoaded = true,
                request = withAttachments(
                    case.request,
                    case.request.memberships.attachments.map { it.value } + deny.id
                ),
            )
            for (level in TraceLevel.entries) {
                val evaluation = shadowed.evaluate(level)
                evaluation.decision.assert().describedAs("$level").isNotInstanceOf(Decision.Permit::class.java)
                if (level != TraceLevel.OFF) {
                    val ambiguous = evaluation.trace!!.unresolved.filterIsInstance<Cause.AmbiguousPolicy>()
                    ambiguous.flatMap { it.policyIds }.assert().contains(deny.policyId)
                }
            }
        }
    }

    /** P8: shuffling the candidates never changes the kind of decision. */
    @Test
    fun `P8 the decision does not depend on the candidate order`() {
        forEachCase(seed = 8) { case, random ->
            val expected = case.evaluate().decision.javaClass
            val enumeration = Oracle.enumerate(case)
            val allows = Oracle.enumerate(case, Effect.ALLOW).candidates
            repeat(3) {
                val candidates = enumeration.candidates.shuffled(random)
                val missing = enumeration.missing.shuffled(random)
                val ambiguous = enumeration.ambiguous.shuffled(random)
                val shuffled =
                    ShuffledEvaluator(
                        case.request,
                        case.view(),
                        candidates,
                        allows.shuffled(random),
                        missing,
                        ambiguous
                    )
                shuffled.evaluate().javaClass.assert().isEqualTo(expected)
            }
        }
    }

    /** P-B1 (RFC 0007 §12): a boundary of one ALLOW that matches everything decides as no boundary does. */
    @Test
    fun `P-B1 a boundary that allows everything is the identity`() {
        var bounded = 0
        forEachCase(seed = 71) { case, random ->
            val open = case.fixture.policy("open-boundary", allow())
            val unbounded = case.copy(boundaries = emptyList(), defaultBoundary = null, tenantBoundary = null)
            val boundedCase = if (case.request.scope.tenant != TenantId.PLATFORM && random.nextBoolean()) {
                unbounded.copy(boundaries = listOf(open), tenantBoundary = open.id)
            } else {
                unbounded.copy(boundaries = listOf(open), defaultBoundary = open.id)
            }
            if (case.request.scope.tenant != TenantId.PLATFORM) bounded++
            for (level in TraceLevel.entries) {
                boundedCase.evaluate(level).decision.assert().describedAs("$level")
                    .isEqualTo(unbounded.evaluate(level).decision)
            }
        }
        bounded.assert().isGreaterThan(iterations / 3)
    }

    /** P-B2: a boundary only narrows: whatever it is, it never turns a Deny or an Indeterminate into a Permit. */
    @Test
    fun `P-B2 a boundary never permits what is not permitted without it`() {
        var narrowed = 0
        forEachCase(seed = 72) { case, _ ->
            val unbounded = case.copy(boundaries = emptyList(), defaultBoundary = null, tenantBoundary = null)
            val without = unbounded.evaluate().decision
            for (level in TraceLevel.entries) {
                val with = case.evaluate(level).decision
                if (with is Decision.Permit) {
                    with.assert().describedAs("$level").isEqualTo(without)
                }
            }
            if (without is Decision.Permit && case.evaluate().decision !is Decision.Permit) narrowed++
        }
        // The generator holds some permits to a boundary that refuses them.
        narrowed.assert().isGreaterThan(10)
    }

    /** P-B3: P8 with a boundary: shuffling the candidates never changes the kind of decision. */
    @Test
    fun `P-B3 a boundary decision does not depend on the candidate order`() {
        forEachCase(seed = 73) { case, random ->
            val boundary = case.fixture.policy(
                "b",
                *Array(random.nextInt(1, 4)) { allow(RuleBehavior.entries.random(random)) },
            )
            val bounded = case.copy(boundaries = listOf(boundary), defaultBoundary = boundary.id, tenantBoundary = null)
            val expected = bounded.evaluate().decision.javaClass
            val enumeration = Oracle.enumerate(bounded)
            val allows = Oracle.enumerate(bounded, Effect.ALLOW).candidates
            repeat(3) {
                val shuffled = ShuffledEvaluator(
                    bounded.request,
                    bounded.view(),
                    enumeration.candidates.shuffled(random),
                    allows.shuffled(random),
                    enumeration.missing.shuffled(random),
                    enumeration.ambiguous.shuffled(random),
                )
                shuffled.evaluate().javaClass.assert().isEqualTo(expected)
            }
        }
    }

    /**
     * RC1-1 (RFC 0009 §4.2, `BND-9`, `IDN-2`): a cap only narrows. A capped credential is permitted only what its
     * principal is, and the DENY pass decides the same for both, a matching DENY bound to the principal or attached
     * included: a cap removes ALLOW candidates, never DENYs.
     */
    @Test
    fun `P-cap a cap removes allows only`() {
        forEachCase(seed = 91) { case, random ->
            for (subject in listOf(case, case.withMatchingDeny(random))) {
                val capped = subject.capped()
                DenyPass(capped.request, capped.view()).run().assert()
                    .isEqualTo(DenyPass(subject.request, subject.view()).run())
                val decision = capped.evaluate().decision
                if (decision is Decision.Permit) {
                    // Possibly by a candidate the cap removed, which comes earlier.
                    subject.evaluate().decision.assert().isInstanceOf(Decision.Permit::class.java)
                }
            }
        }
    }

    /** P9: a document that cannot be read, or an attached policy that is gone, never turns a refusal into a permit. */
    @Test
    fun `P9 missing and unavailable data never permit`() {
        forEachCase(seed = 9) { case, random ->
            if (case.evaluate().decision is Decision.Permit) return@forEachCase
            for (degraded in case.degradations(random)) {
                val decision = degraded.evaluate().decision
                decision.assert().describedAs(degraded.describe()).isNotInstanceOf(Decision.Permit::class.java)
            }
        }
    }

    /**
     * P12: another tenant's document never changes a decision, even one with this principal's id, roles and attached
     * ids; one naming this tenant's principal or roles does not compile.
     */
    @Test
    fun `P12 another tenant's document never changes a decision`() {
        forEachCase(seed = 12) { case, random ->
            val tenant = case.request.scope.tenant.value
            val other = if (tenant == "acme") "beta" else "acme"
            val hostile = List(2) { FlatGenerator(random).hostileTenant(other, case) }
            val decisions = hostile.map { document ->
                // Keyed by tenant, as the snapshot source is: the request's tenant picks the document.
                val source = mapOf(tenant to case.tenantDocument, other to document)
                val view = case.view(tenant = source.getValue(tenant).takeIf { case.tenantLoaded })
                CoSecFlatEngine.evaluate(case.request, view).decision
            }
            decisions.distinct().assert().hasSize(1)
            decisions[0].assert().isEqualTo(case.evaluate().decision)
            assertThrownBy<IllegalArgumentException> {
                CoSecFlatEngine.evaluate(case.request, case.view(tenant = hostile[0]))
            }
            for (binding in FlatGenerator(random).hostileBindings(case)) {
                assertThrownBy<IllegalArgumentException> {
                    CompiledDocument.tenant(
                        TenantId(other),
                        listOf(case.fixture.policy("h-deny", deny())),
                        listOf(binding)
                    )
                }
            }
        }
    }

    private class Collector : CandidateVisitor {
        val candidates = mutableListOf<Candidate>()
        val expired = mutableListOf<BindingRef.Bound>()
        val missing = linkedSetOf<String>()
        val ambiguous = linkedSetOf<String>()

        override fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean {
            candidates += Candidate(policy, via)
            return false
        }

        override fun expired(via: BindingRef.Bound) {
            expired += via
        }

        override fun missing(policyId: String) {
            missing += policyId
        }

        override fun ambiguous(policyId: String) {
            ambiguous += policyId
        }
    }

    /** The DENY pass alone: its decision, or null when the ALLOW pass would decide. */
    private class DenyPass(request: FlatRequest, view: SnapshotView) : FlatEvaluator(request, view) {
        fun run(): Decision? = denyPass()
    }

    private class ShuffledEvaluator(
        request: FlatRequest,
        view: SnapshotView,
        private val denies: List<Candidate>,
        private val allows: List<Candidate>,
        private val missingIds: List<String>,
        private val ambiguousIds: List<String>,
    ) : FlatEvaluator(request, view) {
        override fun enumerate(): Boolean {
            missingIds.forEach(::missing)
            ambiguousIds.forEach(::ambiguous)
            return (if (pass == Effect.DENY) denies else allows).any { candidate(it.policy, it.via) }
        }
    }
}
