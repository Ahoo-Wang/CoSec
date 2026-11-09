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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

/** What a window counts for the pairs of decisions that are no longer read as `SAME`, and those that still are. */
class ShadowOutcomeTest {
    private val platform = CompiledDocument.platform(emptyList(), emptyList())
    private val opened = ArrayList<DecisionListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    private inner class Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            platform,
            null,
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
            emptyList(),
            ViewVersion(7, null, null),
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private fun counted(enforced: Decision, engine: FlatEngine): Map<ShadowOutcome, Long> {
        val recorder = ShadowRecorder("instance-1")
        val shadow = ShadowListener(
            "shadow",
            1.0,
            { key -> Candidate.Ready("w", 7, platform).takeIf { key == SnapshotKey.Platform } },
            recorder,
            engine,
        )
        val listeners = DecisionListeners(listOf(shadow)).also { opened += it }
        val pipeline = PipelineFixtures.pipeline(engine = StubEngine(enforced), views = Views()) {
            it.copy(listeners = listeners)
        }
        return runBlocking {
            pipeline.evaluate(PipelineFixtures.facts())
            withTimeout(5.seconds) {
                while (recorder.reports().isEmpty()) {
                    delay(5)
                }
            }
            recorder.reports().single().counts
        }
    }

    private fun outcome(enforced: Decision, candidate: Decision): ShadowOutcome =
        counted(enforced, StubEngine(candidate)).keys.single()

    @Test
    fun `a candidate that denies where enforcement could not decide is not the same`() {
        outcome(INDETERMINATE, Decision.Deny(null)).assert().isEqualTo(ShadowOutcome.BECAME_DENIED)
    }

    @Test
    fun `a candidate that changes a permit's rate limits is not the same`() {
        outcome(permit(LIMITED), permit()).assert().isEqualTo(ShadowOutcome.OBLIGATIONS_CHANGED)
        outcome(permit(), permit(LIMITED)).assert().isEqualTo(ShadowOutcome.OBLIGATIONS_CHANGED)
    }

    @Test
    fun `the same permit with the same rate limits is the same`() {
        outcome(permit(LIMITED), permit(LIMITED)).assert().isEqualTo(ShadowOutcome.SAME)
    }

    @Test
    fun `two indeterminate decisions of one kind are the same`() {
        val other = Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId("other"))))
        outcome(INDETERMINATE, other).assert().isEqualTo(ShadowOutcome.SAME)
    }

    /** RC1-43: a candidate this instance cannot evaluate blocks its window, whatever it fails with. */
    @Test
    fun `a candidate whose evaluation overflows the stack is an error`() {
        val overflowing = StubEngine { throw StackOverflowError() }
        counted(permit(), overflowing).assert().isEqualTo(mapOf(ShadowOutcome.CANDIDATE_ERROR to 1L))
    }

    private companion object {
        val INDETERMINATE = Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId("missing"))))
        val LIMITED = Obligation.RateLimit("rule", "k:a", RateLimitSpec(1.0), false)

        fun permit(vararg obligations: Obligation) = Decision.Permit(
            StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Attached(PolicyId("p"))),
            obligations.toList(),
        )
    }
}
