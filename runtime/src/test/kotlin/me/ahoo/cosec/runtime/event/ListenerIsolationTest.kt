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

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.Basis
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.EXPLICIT_DENY
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.ThreadingFixtures.EVENT_LOOP
import me.ahoo.cosec.runtime.ThreadingFixtures.singleThread
import me.ahoo.cosec.runtime.ThreadingFixtures.startOn
import me.ahoo.cosec.runtime.Unavailability
import me.ahoo.cosec.runtime.audit.AuditEvents
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * RFC 0006 §9.3, §9.4: listeners observe and never decide. A BEST_EFFORT listener that fails, hangs or falls behind
 * never changes, holds up or fails a request, and never runs on the request's thread; a REQUIRED one that fails or times
 * out turns only a permit into 503.
 */
class ListenerIsolationTest {
    private val eventLoop = singleThread(EVENT_LOOP)
    private val opened = ArrayList<DecisionListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
        eventLoop.shutdownNow()
    }

    /** Records every event and the thread it was handled on. */
    private class Recording(
        override val name: String = "recording",
        override val delivery: Delivery = Delivery.BEST_EFFORT,
        private val before: suspend () -> Unit = {},
    ) : DecisionListener {
        val events = CopyOnWriteArrayList<DecisionEvent>()
        val threads = CopyOnWriteArrayList<Thread>()

        override suspend fun onDecision(event: DecisionEvent) {
            before()
            threads += Thread.currentThread()
            events += event
        }

        fun await(count: Int): List<DecisionEvent> = runBlocking {
            withTimeout(5.seconds) {
                while (events.size < count) {
                    kotlinx.coroutines.delay(5)
                }
            }
            events.toList()
        }
    }

    private class Failing(
        override val name: String,
        override val delivery: Delivery,
        private val fail: suspend () -> Unit = { error("$name is down") },
    ) : DecisionListener {
        override suspend fun onDecision(event: DecisionEvent) = fail()
    }

    private fun listeners(vararg listeners: DecisionListener, queueCapacity: Int = 16) =
        DecisionListeners(listeners.toList(), requiredTimeout = 50.milliseconds, queueCapacity = queueCapacity)
            .also { opened += it }

    private fun evaluate(
        listeners: DecisionListeners,
        decision: Decision = PERMIT,
        authenticator: Authenticator = authenticator(authenticated()),
        path: String = "/orders",
    ): Outcome = runBlocking {
        PipelineFixtures.pipeline(authenticator, StubEngine(decision), listeners = listeners).evaluate(facts(path))
    }

    private val Outcome.cause: Cause
        get() = ((basis as Basis.Unavailable).reason as Unavailability.Indeterminate).cause

    // --- BEST_EFFORT -------------------------------------------------------------------------------------------

    @Test
    fun aFailingBestEffortListenerNeverChangesTheOutcome() {
        val recording = Recording()
        val failing = Failing("failing", Delivery.BEST_EFFORT)
        val listeners = listeners(failing, recording)
        evaluate(listeners).status.assert().isEqualTo(200)
        evaluate(listeners, EXPLICIT_DENY).status.assert().isEqualTo(403)
        recording.await(2)
        val stats = listeners.stats.single { it.listener == "failing" }
        runBlocking {
            withTimeout(5.seconds) {
                while (stats.failures < 2) {
                    kotlinx.coroutines.delay(5)
                }
            }
        }
    }

    /** RFC 0007 §6.4: a candidate source that fails or hangs costs the shadow its samples, never a request its outcome. */
    @Test
    fun aFailingOrHangingCandidateSourceNeverChangesTheOutcome() {
        val recorder = ShadowRecorder("instance-1")
        val failing = ShadowListener("failing-shadow", 1.0, { error("the candidates are down") }, recorder)
        val hanging = ShadowListener("hanging-shadow", 1.0, { kotlinx.coroutines.awaitCancellation() }, recorder)
        val recording = Recording()
        val listeners = listeners(failing, hanging, recording)
        evaluate(listeners).status.assert().isEqualTo(200)
        evaluate(listeners, EXPLICIT_DENY).status.assert().isEqualTo(403)
        recording.await(2)
        recorder.reports().assert().isEmpty()
    }

    @Test
    fun aHangingBestEffortListenerNeitherHoldsTheRequestNorCostsAnotherItsEvents() {
        val recording = Recording()
        val hanging = Failing("hanging", Delivery.BEST_EFFORT) { awaitCancellation() }
        val listeners = listeners(hanging, recording)
        repeat(3) { evaluate(listeners).status.assert().isEqualTo(200) }
        recording.await(3).size.assert().isEqualTo(3)
    }

    @Test
    fun aBestEffortListenerNeverRunsOnTheRequestThread() {
        val recording = Recording()
        val listeners = listeners(recording)
        val pipeline = PipelineFixtures.pipeline(
            authenticator(authenticated()),
            StubEngine(PERMIT),
            listeners = listeners
        )
        val (outcome, finishedOn) = startOn(eventLoop, EmptyCoroutineContext) { pipeline.evaluate(facts()) }
        outcome.proceed.assert().isTrue()
        finishedOn.name.assert().isEqualTo(EVENT_LOOP)
        recording.await(1)
        recording.threads.single().name.assert().isNotEqualTo(EVENT_LOOP)
    }

    @Test
    fun aFullQueueDropsEventsAndNeverBlocks() {
        val release = CompletableDeferred<Unit>()
        val stuck = Recording("stuck") { release.await() }
        val listeners = listeners(stuck, queueCapacity = 1)
        repeat(5) { evaluate(listeners).status.assert().isEqualTo(200) }
        listeners.stats.single().dropped.assert().isGreaterThanOrEqualTo(3)
        release.complete(Unit)
    }

    // --- REQUIRED ----------------------------------------------------------------------------------------------

    @Test
    fun aFailingRequiredListenerTurnsAPermitInto503() {
        val recording = Recording()
        val listeners = listeners(Failing("audit", Delivery.REQUIRED), recording)
        val outcome = evaluate(listeners)
        outcome.status.assert().isEqualTo(503)
        outcome.problem!!.type.slug.assert().isEqualTo("unavailable")
        outcome.cause.assert().isEqualTo(Cause.RequiredListenerFailed(listOf("audit")))
        val event = recording.await(1).single()
        event.outcome.status.assert().isEqualTo(503)
        event.requiredFailures.assert().containsExactly("audit")
        event.decision.assert().isEqualTo(PERMIT)
    }

    @Test
    fun aRequiredListenerThatDoesNotAnswerInTimeTurnsAPermitInto503() {
        val listeners = listeners(Failing("audit", Delivery.REQUIRED) { awaitCancellation() })
        evaluate(listeners).status.assert().isEqualTo(503)
        listeners.stats.single().failures.assert().isEqualTo(1)
    }

    /** Q18: a 403 stays a 403; making it a 503 would only tell the client to retry. */
    @Test
    fun aFailingRequiredListenerKeepsEveryOtherStatus() {
        val listeners = listeners(Failing("audit", Delivery.REQUIRED))
        evaluate(listeners, EXPLICIT_DENY).status.assert().isEqualTo(403)
        evaluate(listeners, path = "/a/%2e%2e/b").status.assert().isEqualTo(400)
        listeners.requiredFailuresOnNonPermits.assert().isEqualTo(2)
    }

    /** Review R2: a slow REQUIRED listener costs a deny time, never its status; a permit still keeps the deadline. */
    @Test
    fun aSlowRequiredListenerKeepsADenyAndHoldsAPermitToTheDeadline() {
        val slow = Failing("audit", Delivery.REQUIRED) { kotlinx.coroutines.delay(500) }
        val listeners = DecisionListeners(listOf(slow), requiredTimeout = 5.seconds).also { opened += it }
        val options = me.ahoo.cosec.runtime.PipelineOptions(timeout = 100.milliseconds)
        fun evaluate(decision: Decision, authenticator: Authenticator) = runBlocking {
            PipelineFixtures.pipeline(authenticator, StubEngine(decision), options = options, listeners = listeners)
                .evaluate(facts())
        }
        evaluate(Decision.Deny(null), Authenticator { AuthnResult.Anonymous }).status.assert().isEqualTo(401)
        val permit = evaluate(PERMIT, authenticator(authenticated()))
        permit.status.assert().isEqualTo(503)
        ((permit.basis as Basis.Unavailable).reason).assert().isEqualTo(Unavailability.DeadlineExceeded)
        listeners.stats.single().failures.assert().isZero()
    }

    @Test
    fun requiredListenersSeeTheOutcomeBeforeThemAndBestEffortOnesTheFinalOne() {
        val required = Recording("audit", Delivery.REQUIRED)
        val failing = Failing("other", Delivery.REQUIRED)
        val bestEffort = Recording()
        evaluate(listeners(required, failing, bestEffort)).status.assert().isEqualTo(503)
        required.events.single().outcome.status.assert().isEqualTo(200)
        required.events.single().requiredFailures.assert().isEmpty()
        bestEffort.await(1).single().outcome.status.assert().isEqualTo(503)
    }

    @Test
    fun succeedingRequiredListenersLetAPermitProceed() {
        val required = Recording("audit", Delivery.REQUIRED)
        evaluate(listeners(required, Recording("other", Delivery.REQUIRED))).status.assert().isEqualTo(200)
        required.events.single().outcome.proceed.assert().isTrue()
    }

    // --- every outcome has an event ------------------------------------------------------------------------------

    @Test
    fun everyOutcomeHasAnEvent() {
        val recording = Recording()
        val listeners = listeners(recording)
        evaluate(listeners, path = "/a/%2e%2e/b")
        evaluate(listeners, authenticator = Authenticator { AuthnResult.Unavailable(IllegalStateException("down")) })
        runBlocking {
            PipelineFixtures.pipeline(
                authenticator(authenticated()),
                StubEngine { error("a defect") },
                listeners = listeners
            )
                .evaluate(facts())
        }.status.assert().isEqualTo(500)
        evaluate(listeners, decision = Decision.Deny(null), authenticator = Authenticator { AuthnResult.Anonymous })
        recording.await(4).map { it.kind }.assert().containsExactly(
            DecisionEvent.Kind.BAD_REQUEST,
            DecisionEvent.Kind.AUTHENTICATION_UNAVAILABLE,
            DecisionEvent.Kind.ERROR,
            DecisionEvent.Kind.DECIDED,
        )
        val decided = recording.events.last()
        decided.outcome.reason.assert().isEqualTo(OutcomeSummary.Reason.IMPLICIT_DENY)
        decided.subject.authenticated.assert().isFalse()
        decided.request.path.assert().isEqualTo("/orders")
        decided.snapshotVersion.assert().isNull()
    }

    @Test
    fun aCancelledRequestHasAnEventAndNoOutcome() = runBlocking<Unit> {
        val recording = Recording()
        val listeners = listeners(recording)
        val reached = CompletableDeferred<Unit>()
        val hanging = Authenticator {
            reached.complete(Unit)
            awaitCancellation()
        }
        val pipeline = PipelineFixtures.pipeline(hanging, StubEngine(PERMIT), listeners = listeners)
        var outcome: Outcome? = null
        val caller = launch { outcome = pipeline.evaluate(facts()) }
        reached.await()
        caller.cancelAndJoin()
        outcome.assert().isNull()
        val event = recording.await(1).single()
        event.kind.assert().isEqualTo(DecisionEvent.Kind.CANCELLED)
        event.outcome.reason.assert().isEqualTo(OutcomeSummary.Reason.CANCELLED)
    }

    /** §9.2: no header, token or attribute value is in an event, nor in the audit record made from it. */
    @Test
    fun eventsCarryNoTokenOrHeader() {
        val recording = Recording()
        val listeners = listeners(recording)
        runBlocking {
            PipelineFixtures.pipeline(authenticator(authenticated()), StubEngine(PERMIT), listeners = listeners)
                .evaluate(
                    facts(
                        headers = mapOf("Authorization" to listOf("Bearer secret-token"), "X-Secret" to listOf("hush"))
                    )
                )
        }
        val event = recording.await(1).single()
        val audit = CoSecJsonSerializer.writeValueAsString(AuditEvents.from(event))
        audit.assert().doesNotContain("secret-token").doesNotContain("hush")
        event.toString().assert().doesNotContain("secret-token")
    }

    @Test
    fun noListenersMakeNoEvent() {
        DecisionListeners.NONE.isEmpty.assert().isTrue()
        evaluate(DecisionListeners.NONE).status.assert().isEqualTo(200)
    }

    @Test
    fun listenerNamesAreUnique() {
        val twice = listOf(Recording(), Recording())
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { DecisionListeners(twice) }
    }
}
