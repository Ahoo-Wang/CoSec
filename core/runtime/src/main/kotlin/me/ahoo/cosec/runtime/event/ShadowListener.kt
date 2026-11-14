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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.shadow.ShadowSample
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewDocuments
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Evaluates a sample of the requests again on the staged candidates (RFC 0006 §9.5.4, RFC 0007 §6.4) and records how
 * each came out in [recorder], per shadow window. Always BEST_EFFORT, on the listeners' consumer: it never runs
 * obligations, never triggers REQUIRED listeners, never fetches an attribute (a candidate statement reading one the
 * request did not fetch finds it missing: [ShadowOutcome.CANDIDATE_INDETERMINATE]), and nothing it does reaches the
 * enforced decision or the enforced view: the candidate view is a new one, made for this comparison only.
 *
 * Each document the request's view read (the platform's always, the app's and the tenant's when it read them) is looked
 * up in [candidates]. A candidate is compared only when its base revision is the revision this view has of the
 * document; otherwise it counts as [ShadowOutcome.BASE_MISMATCH]. So a tenant's candidate is evaluated on that tenant's
 * requests only, and the platform's on every request.
 *
 * Whether a request is sampled is decided before its event is made ([sample]); only then does the event carry the
 * [ReplayHandle] that keeps its view reachable.
 */
@InternalCoSecApi
class ShadowListener(
    override val name: String,
    /** The share of the requests evaluated again, from 0 to 1. */
    private val sampleRate: Double,
    private val candidates: CandidateSource,
    private val recorder: ShadowRecorder,
    private val engine: FlatEngine = CoSecFlatEngine,
    /**
     * The installed documents, for the app document a tenant candidate makes a request read that its live view did not
     * ([ViewDocuments.appOf], RC1-17). Without them such a candidate cannot be compared: [ShadowOutcome.CANDIDATE_ERROR].
     */
    private val views: SnapshotViews? = null,
) : DecisionListener {
    init {
        require(sampleRate in 0.0..1.0) { "The shadow sample rate is between 0 and 1, not $sampleRate." }
    }

    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    internal fun sample(): Boolean = sampleRate > 0 && (sampleRate >= 1 || ThreadLocalRandom.current().nextDouble() < sampleRate)

    override suspend fun onDecision(event: DecisionEvent) {
        val replay = event.replay ?: return
        val enforced = event.decision ?: return
        if (name !in replay.sampledBy) {
            return
        }
        for ((key, installed) in documents(replay.view)) {
            when (val candidate = candidates.candidate(key)) {
                null -> Unit
                is Candidate.Failed -> recorder.record(key, candidate.window, ShadowOutcome.CANDIDATE_ERROR, null, null)
                is Candidate.Ready -> compare(replay, enforced, key, installed, candidate)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun compare(
        replay: ReplayHandle,
        enforced: Decision,
        key: SnapshotKey,
        installed: Long?,
        candidate: Candidate.Ready
    ) {
        if (installed == null || installed != candidate.baseRevision) {
            recorder.record(key, candidate.window, ShadowOutcome.BASE_MISMATCH, installed, null)
            return
        }
        val decided = try {
            val view = candidateView(replay, key, candidate.document)
            if (view == null) {
                log.debug { "The candidate of window ${candidate.window} needs an app document no view source gives." }
                recorder.record(key, candidate.window, ShadowOutcome.CANDIDATE_ERROR, installed, null)
                return
            }
            engine.evaluate(replay.request, view, TraceLevel.OFF).decision
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedToEvaluate(replay, enforced, key, installed, candidate, e)
            return
        } catch (e: StackOverflowError) {
            // As a compilation that overflows (RC1-43): an instance that cannot evaluate the candidate blocks its window.
            failedToEvaluate(replay, enforced, key, installed, candidate, e)
            return
        }
        val outcome = outcome(enforced, decided)
        recorder.record(
            key,
            candidate.window,
            outcome,
            installed,
            sample(replay.request, enforced, decided).takeIf {
                outcome != ShadowOutcome.SAME
            }
        )
    }

    private fun failedToEvaluate(
        replay: ReplayHandle,
        enforced: Decision,
        key: SnapshotKey,
        installed: Long?,
        candidate: Candidate.Ready,
        error: Throwable,
    ) {
        log.debug(error) { "The candidate of window ${candidate.window} failed to evaluate." }
        recorder.record(
            key,
            candidate.window,
            ShadowOutcome.CANDIDATE_ERROR,
            installed,
            sample(replay.request, enforced, null)
        )
    }

    /** The documents [view] read, with the revisions it had of them (null when unknown). */
    private fun documents(view: SnapshotView): List<Pair<SnapshotKey, Long?>> = buildList {
        add(SnapshotKey.Platform to view.version?.platform)
        view.app?.let { add(SnapshotKey.App(it.owner) to view.version?.app) }
        view.tenant?.let { add(SnapshotKey.Tenant(it.owner) to view.version?.tenant) }
    }

    /**
     * The view [document], the candidate of [key], is evaluated on, as enforcement would read it once the candidate is
     * published: the replay's view with [key]'s document replaced and, for a tenant candidate, with the app document the
     * one rule ([ViewDocuments.appOf]) makes the request read with that tenant document. One it needs and the view lacks
     * is read from the installed documents ([views]); one it no longer needs is left out. Null when one is needed and
     * there is no [views].
     */
    private suspend fun candidateView(
        replay: ReplayHandle,
        key: SnapshotKey,
        document: CompiledDocument
    ): SnapshotView? {
        val view = replaced(replay.view, key, document)
        if (key !is SnapshotKey.Tenant) {
            return view
        }
        val scope = replay.request.scope
        val app = ViewDocuments.appOf(scope) { document.needsApp(it) }
        return when {
            app == null -> if (view.app == null) view else withApp(view, null)
            view.app != null -> view
            else -> {
                val source = views ?: return null
                val withApp = ViewKey.of(scope.tenant, app)
                withApp(view, (source.peek(withApp) ?: source.load(withApp).await()).app)
            }
        }
    }

    private fun withApp(view: SnapshotView, app: CompiledDocument?): SnapshotView =
        SnapshotView(view.platform, app, view.tenant, view.unresolved, view.version)

    /** A new view: [view] with [key]'s document replaced. The enforced view is never changed. */
    private fun replaced(view: SnapshotView, key: SnapshotKey, document: CompiledDocument): SnapshotView {
        val expected = when (key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM
            is SnapshotKey.App -> DocumentKind.APP
            else -> DocumentKind.TENANT
        }
        require(document.kind == expected && (expected == DocumentKind.PLATFORM || document.owner == ownerOf(key))) {
            "The candidate of [$key] is a ${document.kind} document of [${document.owner}]."
        }
        return when (expected) {
            DocumentKind.PLATFORM -> SnapshotView(document, view.app, view.tenant, view.unresolved, view.version)
            DocumentKind.APP -> SnapshotView(view.platform, document, view.tenant, view.unresolved, view.version)
            DocumentKind.TENANT -> SnapshotView(view.platform, view.app, document, view.unresolved, view.version)
        }
    }

    private fun ownerOf(key: SnapshotKey): String = when (key) {
        is SnapshotKey.App -> key.appId
        is SnapshotKey.Tenant -> key.tenantId
        else -> ""
    }

    private fun outcome(enforced: Decision, candidate: Decision): ShadowOutcome = when {
        enforced == candidate -> ShadowOutcome.SAME
        candidate is Decision.Indeterminate && enforced !is Decision.Indeterminate ->
            if (candidate.cause is Cause.AttributeUnavailable) {
                ShadowOutcome.CANDIDATE_INDETERMINATE
            } else {
                ShadowOutcome.BECAME_INDETERMINATE
            }

        candidate is Decision.Permit && enforced !is Decision.Permit -> ShadowOutcome.WIDENED
        enforced is Decision.Permit && candidate !is Decision.Permit -> ShadowOutcome.NARROWED
        enforced is Decision.Indeterminate && candidate is Decision.Deny -> ShadowOutcome.BECAME_DENIED
        else -> sameResult(enforced, candidate)
    }

    /** What differs, if anything, between two decisions that come to the same result but are not equal. */
    private fun sameResult(enforced: Decision, candidate: Decision): ShadowOutcome = when {
        enforced::class == candidate::class && by(enforced) != by(candidate) -> ShadowOutcome.BY_CHANGED
        enforced is Decision.Permit && candidate is Decision.Permit && enforced.obligations != candidate.obligations ->
            ShadowOutcome.OBLIGATIONS_CHANGED
        else -> ShadowOutcome.SAME
    }

    /** The fields of a decision event a sample keeps (RFC 0006 §9.2): no header, token or attribute value. */
    private fun sample(request: FlatRequest, enforced: Decision, candidate: Decision?): ShadowSample = ShadowSample(
        tenant = request.scope.tenant.value,
        principal = request.request.subject.id,
        app = request.scope.app.value,
        space = request.scope.space.value,
        action = "${request.request.action.namespace}:${request.request.action.verb}",
        resource = "${request.request.resource.type}/${request.request.resource.id}",
        enforcedBy = by(enforced),
        candidateBy = candidate?.let(::by),
    )

    private fun by(decision: Decision): String? {
        val ref = when (decision) {
            is Decision.Permit -> decision.by
            is Decision.Deny -> decision.by
            is Decision.Indeterminate -> return decision.cause::class.simpleName
        }
        return when (ref) {
            is StatementRef.Policy -> "${ref.policy.value}[${ref.index}] via ${ref.via}"
            null -> null
            else -> ref.toString()
        }
    }

    override fun toString(): String = "ShadowListener($name, sampleRate=$sampleRate)"
}
