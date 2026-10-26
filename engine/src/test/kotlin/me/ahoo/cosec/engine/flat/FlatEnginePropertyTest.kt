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
            val expected = Oracle.enumerate(case)
            val collector = Collector()
            CandidateOrder.forEach(case.request, case.view(), collector)
            collector.candidates.assert().isEqualTo(expected.candidates)
            collector.expired.assert().isEqualTo(expected.expired)
            collector.missing.assert().isEqualTo(expected.missing)
            collector.ambiguous.assert().isEqualTo(expected.ambiguous)
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
            repeat(3) {
                val candidates = enumeration.candidates.shuffled(random)
                val missing = enumeration.missing.shuffled(random)
                val ambiguous = enumeration.ambiguous.shuffled(random)
                val shuffled = ShuffledEvaluator(case.request, case.view(), candidates, missing, ambiguous)
                shuffled.evaluate().javaClass.assert().isEqualTo(expected)
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

    private class ShuffledEvaluator(
        request: FlatRequest,
        view: SnapshotView,
        private val candidates: List<Candidate>,
        private val missingIds: List<String>,
        private val ambiguousIds: List<String>,
    ) : FlatEvaluator(request, view) {
        override fun enumerate(): Boolean {
            missingIds.forEach(::missing)
            ambiguousIds.forEach(::ambiguous)
            return candidates.any { candidate(it.policy, it.via) }
        }
    }
}
