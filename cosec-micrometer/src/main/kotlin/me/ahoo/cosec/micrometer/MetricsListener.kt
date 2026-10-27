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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import java.util.concurrent.TimeUnit

/**
 * Records every decision event as the timer [METRIC_NAME] (RFC 0006 §9.5.2), over the whole pipeline, not only the
 * authorization (X4). BEST_EFFORT: it runs off the request path.
 *
 * Every tag is a small fixed enumeration, never a policy, app or tenant id, so the number of series stays bounded:
 * - [RESULT_TAG]: `allow`, `explicit_deny`, `implicit_deny`, `indeterminate`, `bad_request`, `cancelled`, `error`;
 * - [SOURCE_TAG]: how the deciding statement applied: `binding`, `attachment`, `builtin`, `scope` or `none`;
 * - [CAUSE_TAG], on `indeterminate` only: `source_unavailable`, `missing_policies`, `membership`, `attribute`,
 *   `matcher`, `authenticator`, `required_listener` or `deadline`.
 */
class MetricsListener(private val registry: MeterRegistry) : DecisionListener {
    override val name: String get() = NAME

    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    override suspend fun onDecision(event: DecisionEvent) {
        val result = result(event.outcome.reason)
        var tags = Tags.of(RESULT_TAG, result, SOURCE_TAG, source(event.by))
        if (result == INDETERMINATE) {
            tags = tags.and(CAUSE_TAG, cause(event))
        }
        Timer.builder(METRIC_NAME)
            .description("CoSec decisions, over the whole pipeline")
            .tags(tags)
            .register(registry)
            .record(event.elapsedNanos, TimeUnit.NANOSECONDS)
    }

    private fun result(reason: Reason): String = when (reason) {
        Reason.PERMIT -> "allow"
        Reason.EXPLICIT_DENY, Reason.SCOPE_REJECTED, Reason.BOUNDARY -> "explicit_deny"
        Reason.IMPLICIT_DENY -> "implicit_deny"
        Reason.INDETERMINATE, Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED -> INDETERMINATE
        Reason.INVALID_REQUEST -> "bad_request"
        Reason.CANCELLED -> "cancelled"
        Reason.ERROR -> "error"
    }

    private fun source(by: StatementRef?): String = when (by) {
        is StatementRef.Policy -> when (by.via) {
            is BindingRef.Bound -> "binding"
            is BindingRef.Attached -> "attachment"
            BindingRef.Builtin -> "builtin"
            is BindingRef.Boundary -> "boundary"
        }
        is StatementRef.ScopeRejected -> "scope"
        is StatementRef.Boundary -> "boundary"
        null -> "none"
    }

    private fun cause(event: DecisionEvent): String = when (event.outcome.reason) {
        Reason.AUTHENTICATOR_UNAVAILABLE -> "authenticator"
        Reason.DEADLINE_EXCEEDED -> "deadline"
        else -> when (event.outcome.cause) {
            is Cause.SourceUnavailable, is Cause.NotLoaded -> "source_unavailable"
            is Cause.MissingPolicies, is Cause.AmbiguousPolicy -> "missing_policies"
            is Cause.MembershipUnavailable -> "membership"
            is Cause.AttributeUnavailable -> "attribute"
            is Cause.MatcherFailed -> "matcher"
            is Cause.RequiredListenerFailed -> "required_listener"
            is Cause.BoundaryUnavailable -> "boundary"
            null -> "unknown"
        }
    }

    companion object {
        const val NAME = "metrics"
        const val METRIC_NAME = "cosec.authorization"
        const val RESULT_TAG = "result"
        const val SOURCE_TAG = "source"
        const val CAUSE_TAG = "cause"
        const val LISTENER_TAG = "listener"
        private const val INDETERMINATE = "indeterminate"

        /**
         * Registers what [listeners] count (§9.5.2): `cosec.listener.dropped` and `cosec.listener.failures` by listener,
         * and `cosec.audit.required.failures`.
         */
        fun bind(registry: MeterRegistry, listeners: DecisionListeners) {
            for (stats in listeners.stats) {
                FunctionCounter.builder("cosec.listener.dropped", stats) { it.dropped.toDouble() }
                    .description("Decision events a full queue dropped")
                    .tag(LISTENER_TAG, stats.listener)
                    .register(registry)
                FunctionCounter.builder("cosec.listener.failures", stats) { it.failures.toDouble() }
                    .description("Decision listener calls that failed or timed out")
                    .tag(LISTENER_TAG, stats.listener)
                    .register(registry)
            }
            FunctionCounter.builder("cosec.audit.required.failures", listeners) {
                it.requiredFailuresOnNonPermits.toDouble()
            }
                .description("REQUIRED listener failures on decisions they could not change")
                .register(registry)
        }
    }
}
