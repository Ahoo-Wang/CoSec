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

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.authn.UncheckedRevocations
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import me.ahoo.cosec.runtime.identity.IdentityRefusals
import java.util.concurrent.TimeUnit

/**
 * Records every decision event as the timer [METRIC_NAME] (RFC 0006 §9.5.2), over the whole pipeline, not only the
 * authorization (X4). BEST_EFFORT: it runs off the request path.
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
    override val name: String get() = NAME

    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    override suspend fun onDecision(event: DecisionEvent) {
        val result = result(event.outcome.reason)
        // The same tag keys on every series: a Prometheus registry refuses meters of one name with different keys.
        val tags = Tags.of(
            RESULT_TAG,
            result,
            SOURCE_TAG,
            source(event.by),
            PROBLEM_TAG,
            event.outcome.problem?.slug ?: NONE,
            CAUSE_TAG,
            if (result == INDETERMINATE) cause(event) else NONE,
        )
        Timer.builder(METRIC_NAME)
            .description("CoSec decisions, over the whole pipeline")
            .tags(tags)
            .register(registry)
            .record(event.elapsedNanos, TimeUnit.NANOSECONDS)
    }

    /**
     * The event's reason in this tag's case (UD-7): one mapping, exhaustive, so a new Reason without a value does not
     * compile. The two ways a request is indeterminate before the engine ran are `indeterminate`, and [CAUSE_TAG] says
     * which.
     */
    internal fun result(reason: Reason): String = when (reason) {
        Reason.PERMIT -> "permit"
        Reason.TOO_MANY_REQUESTS -> "too_many_requests"
        Reason.EXPLICIT_DENY -> "explicit_deny"
        Reason.IMPLICIT_DENY -> "implicit_deny"
        Reason.SCOPE_REJECTED -> "scope_rejected"
        Reason.BOUNDARY -> "boundary"
        Reason.INVALID_REQUEST -> "invalid_request"
        Reason.INDETERMINATE, Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED -> INDETERMINATE
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
        else -> causeTag(event.outcome.cause)
    }

    companion object {
        /** The `cause` tag of [cause]: one value for each kind, a closed set (`OBS-6`). */
        internal fun causeTag(cause: Cause?): String = when (cause) {
            is Cause.SourceUnavailable, is Cause.NotLoaded -> "source_unavailable"
            is Cause.DocumentInvalid -> "document_invalid"
            is Cause.MissingPolicies, is Cause.AmbiguousPolicy -> "missing_policies"
            is Cause.MembershipUnavailable -> "membership"
            is Cause.AttributeUnavailable -> "attribute"
            is Cause.MatcherFailed -> "matcher"
            is Cause.RequiredListenerFailed -> "required_listener"
            is Cause.BoundaryUnavailable -> "boundary"
            is Cause.RateLimiterUnavailable -> "rate_limiter"
            // Only a check answers it, and a check reaches no listener: never on the timer.
            is Cause.CheckFailed -> "check"
            null -> "unknown"
        }

        const val NAME = "metrics"
        const val METRIC_NAME = "cosec.authorization"
        const val RESULT_TAG = "result"
        const val SOURCE_TAG = "source"
        const val PROBLEM_TAG = "problem"
        const val CAUSE_TAG = "cause"
        const val LISTENER_TAG = "listener"
        const val UNCHECKED_REVOCATIONS = "cosec.revocation.unchecked"
        const val IDENTITY_REFUSED = "cosec.identity.refused"
        private const val INDETERMINATE = "indeterminate"
        private const val NONE = "none"

        /**
         * Registers what [listeners] count (§9.5.2): `cosec.listener.dropped` and `cosec.listener.failures` by listener,
         * and `cosec.audit.required.failures`. The starter's: it holds the listeners.
         */
        @InternalCoSecApi
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

        /**
         * Registers `cosec.revocation.unchecked`: the tokens accepted without a revocation check because the revocation
         * store could not be read (PIP-7). While it rises, a revoked token is accepted.
         */
        fun bind(registry: MeterRegistry, unchecked: UncheckedRevocations) {
            FunctionCounter.builder(UNCHECKED_REVOCATIONS, unchecked) { it.count.toDouble() }
                .description("Tokens accepted without a revocation check, the revocation store unreadable")
                .register(registry)
        }

        /**
         * Registers `cosec.identity.refused`, one series per problem type the gate refuses with (`OBS-7`): the forwarded
         * identities a service in gateway mode refused. The tag's values are that closed set, never anything a request
         * carries. Any non-zero rate is a hop that is misconfigured or a request that did not come through the gateway.
         */
        @InternalCoSecApi
        fun bind(registry: MeterRegistry, refusals: IdentityRefusals) {
            IdentityRefusals.TYPES.forEach { type ->
                FunctionCounter.builder(IDENTITY_REFUSED, refusals) { it.count(type).toDouble() }
                    .tag(PROBLEM_TAG, type.slug)
                    .description("Forwarded identities a service in gateway mode refused, by problem")
                    .register(registry)
            }
        }
    }
}
