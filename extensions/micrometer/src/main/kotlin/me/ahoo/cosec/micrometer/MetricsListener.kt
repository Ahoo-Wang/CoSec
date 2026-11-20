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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import me.ahoo.cosec.runtime.http.ProblemType
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Records every decision event as the timer [METRIC_NAME] (RFC 0006 §9.5.2), over the whole pipeline, not only the
 * authorization (X4). BEST_EFFORT: it runs off the request path.
 *
 * The tag sets are closed, so each combination's timer is resolved once and kept in an array indexed by the four tags
 * (a few hundred bytes of references per combination in use, a fixed few tens of kilobytes at most): an event is
 * four index lookups and a `record`, not a registry lookup by tags. The counters the starter registers beside it are
 * [CoSecMeters].
 *
 * Every tag is a small fixed enumeration, never a policy, app or tenant id, so the number of series stays bounded:
 * - [RESULT_TAG], what decided, in the words of the decision event's `outcome.reason` (UD-7): `permit`,
 *   `too_many_requests`, `explicit_deny` (a DENY statement matched, and only that), `implicit_deny`, `scope_rejected`,
 *   `boundary`, `invalid_request`, `indeterminate`, `cancelled`, `error`;
 * - [SOURCE_TAG]: how the deciding statement applied: `binding`, `attachment`, `builtin`, `boundary`, `scope` or `none`;
 * - [PROBLEM_TAG], what the client was told (UB-8): the slug of the problem type, `unauthorized` (a `401` without a
 *   token), `token-invalid`, `token-expired`, `token-revoked`, `forbidden`, `scope-rejected`, `too-many-requests`,
 *   `invalid-request`, `unavailable`, `internal-error`, or `none` when the request proceeds or was cancelled: the
 *   status code of a refusal is the one of its type, so a `401` and a `403` tell apart, and one token problem from
 *   another;
 * - [CAUSE_TAG], `none` unless the result is `indeterminate`, then: `source_unavailable`, `document_invalid`, `missing_policies`, `membership`,
 *   `attribute`, `matcher`, `authenticator`, `required_listener`, `boundary`, `rate_limiter`, `deadline` or `unknown`.
 */
class MetricsListener(private val registry: MeterRegistry) : DecisionListener {
    private val timers = AtomicReferenceArray<Timer>(RESULTS.size * SOURCES.size * PROBLEMS * CAUSES.size)

    override val name: String get() = NAME
    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    override suspend fun onDecision(event: DecisionEvent) {
        val result = resultIndex(event.outcome.reason)
        val source = sourceIndex(event.by)
        val problem = event.outcome.problem?.let { it.ordinal + 1 } ?: 0
        val cause = if (result == INDETERMINATE_INDEX) causeIndex(event) else NONE_CAUSE
        val slot = ((result * SOURCES.size + source) * PROBLEMS + problem) * CAUSES.size + cause
        val timer = timers.get(slot) ?: resolve(slot, result, source, problem, cause)
        timer.record(event.elapsedNanos, TimeUnit.NANOSECONDS)
    }

    /** The timer of one combination, registered the first time it is seen: the registry hands back the same meter to a race. */
    private fun resolve(slot: Int, result: Int, source: Int, problem: Int, cause: Int): Timer {
        // The same tag keys on every series: a Prometheus registry refuses meters of one name with different keys.
        val tags = Tags.of(
            RESULT_TAG,
            RESULTS[result],
            SOURCE_TAG,
            SOURCES[source],
            PROBLEM_TAG,
            if (problem == 0) NONE else ProblemType.entries[problem - 1].slug,
            CAUSE_TAG,
            CAUSES[cause],
        )
        return Timer.builder(METRIC_NAME)
            .description("CoSec decisions, over the whole pipeline")
            .tags(tags)
            .register(registry)
            .also { timers.set(slot, it) }
    }

    /**
     * The event's reason in this tag's case (UD-7): one mapping, exhaustive, so a new Reason without a value does not
     * compile. The two ways a request is indeterminate before the engine ran are `indeterminate`, and [CAUSE_TAG] says
     * which.
     */
    internal fun result(reason: Reason): String = RESULTS[resultIndex(reason)]

    private fun resultIndex(reason: Reason): Int = when (reason) {
        Reason.PERMIT -> 0
        Reason.TOO_MANY_REQUESTS -> 1
        Reason.EXPLICIT_DENY -> 2
        Reason.IMPLICIT_DENY -> 3
        Reason.SCOPE_REJECTED -> 4
        Reason.BOUNDARY -> 5
        Reason.INVALID_REQUEST -> 6
        Reason.INDETERMINATE, Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED -> INDETERMINATE_INDEX
        Reason.CANCELLED -> 8
        Reason.ERROR -> 9
    }

    private fun sourceIndex(by: StatementRef?): Int = when (by) {
        is StatementRef.Policy -> when (by.via) {
            is BindingRef.Bound -> 0
            is BindingRef.Attached -> 1
            BindingRef.Builtin -> 2
            is BindingRef.Boundary -> 3
        }
        is StatementRef.ScopeRejected -> 4
        is StatementRef.Boundary -> 3
        null -> 5
    }

    private fun causeIndex(event: DecisionEvent): Int = when (event.outcome.reason) {
        Reason.AUTHENTICATOR_UNAVAILABLE -> AUTHENTICATOR_CAUSE
        Reason.DEADLINE_EXCEEDED -> DEADLINE_CAUSE
        else -> causeIndex(event.outcome.cause)
    }

    companion object {
        /** The `cause` tag of [cause]: one value for each kind, a closed set (`OBS-6`). */
        internal fun causeTag(cause: Cause?): String = CAUSES[causeIndex(cause)]

        private fun causeIndex(cause: Cause?): Int = when (cause) {
            is Cause.SourceUnavailable, is Cause.NotLoaded -> 1
            is Cause.DocumentInvalid -> 2
            is Cause.MissingPolicies, is Cause.AmbiguousPolicy -> 3
            is Cause.MembershipUnavailable -> 4
            is Cause.AttributeUnavailable -> 5
            is Cause.MatcherFailed -> 6
            is Cause.RequiredListenerFailed -> 8
            is Cause.BoundaryUnavailable -> 9
            is Cause.RateLimiterUnavailable -> 10
            // Only a check answers it, and a check reaches no listener: never on the timer.
            is Cause.CheckFailed -> 12
            null -> 13
        }

        const val NAME = "metrics"
        const val METRIC_NAME = "cosec.authorization"
        const val RESULT_TAG = "result"
        const val SOURCE_TAG = "source"
        const val PROBLEM_TAG = "problem"
        const val CAUSE_TAG = "cause"
        private const val NONE = "none"

        /** The values of each tag, by the index the mappings above answer; `none` is always the cause at 0. */
        private val RESULTS = listOf(
            "permit", "too_many_requests", "explicit_deny", "implicit_deny", "scope_rejected", "boundary",
            "invalid_request", "indeterminate", "cancelled", "error",
        )
        private val SOURCES = listOf("binding", "attachment", "builtin", "boundary", "scope", "none")
        private val CAUSES = listOf(
            NONE, "source_unavailable", "document_invalid", "missing_policies", "membership", "attribute", "matcher",
            "authenticator", "required_listener", "boundary", "rate_limiter", "deadline", "check", "unknown",
        )
        private val PROBLEMS = ProblemType.entries.size + 1
        private const val INDETERMINATE_INDEX = 7
        private const val NONE_CAUSE = 0
        private const val AUTHENTICATOR_CAUSE = 7
        private const val DEADLINE_CAUSE = 11
    }
}
