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

import me.ahoo.cosec.engine.flat.AttributeStatus
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.runtime.ProblemType
import me.ahoo.cosec.runtime.authn.AuthnResult
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What one request through the pipeline came to (RFC 0006 §9.2): every outcome has one, a 400, an unavailable
 * authenticator, a cancellation and a defect included ([kind]).
 *
 * It carries no external attribute value, no header and no token: what must not be in an audit is not in the event
 * ([attributes] holds statuses only). [replay] is for shadow listeners only, and exists only on a sample hit (§9.5.4).
 */
class DecisionEvent internal constructor(
    val id: String,
    val requestId: String,
    /** When the request started, in epoch milliseconds: `environment.time`. */
    val time: Long,
    /** How long the pipeline took, from the start of [time] to the outcome. */
    val elapsedNanos: Long,
    val kind: Kind,
    val outcome: OutcomeSummary,
    /** The engine's decision; null when the request did not reach it. */
    val decision: Decision?,
    /** Whether the permit applied through a break-glass binding (RFC 0004 §4.8). */
    val breakGlass: Boolean,
    /** The revisions of the documents the decision read, (platform, app, tenant). */
    val snapshotVersion: ViewVersion?,
    val subject: SubjectSummary,
    /** Null when the scope was rejected or not reached. */
    val scope: Scope?,
    val action: Action?,
    val resource: Resource?,
    val request: RequestSummary,
    /** The status of each external attribute fetched for the request, never its value (§5.4.3). */
    val attributes: Map<String, AttributeStatus>,
    /** The trace and span the adapter's bridge captured. */
    val trace: TraceContext?,
    /** The REQUIRED listeners that failed or timed out; only best-effort listeners see any. */
    val requiredFailures: List<String>,
    internal val replay: ReplayHandle?,
) {
    /** The statement that decided, as in the decision. */
    val by: StatementRef?
        get() = when (val decision = decision) {
            is Decision.Permit -> decision.by
            is Decision.Deny -> decision.by
            is Decision.Indeterminate, null -> null
        }

    /** The binding or attachment the deciding statement applied through (RFC 0004 §6.5). */
    val via: BindingRef? get() = (by as? StatementRef.Policy)?.via

    /** A copy for best-effort listeners, after the REQUIRED ones answered. */
    internal fun final(outcome: OutcomeSummary, requiredFailures: List<String>, replay: ReplayHandle?) = DecisionEvent(
        id, requestId, time, elapsedNanos, kind, outcome, decision, breakGlass, snapshotVersion, subject, scope, action,
        resource, request, attributes, trace, requiredFailures, replay,
    )

    override fun toString(): String = "DecisionEvent(id=$id, requestId=$requestId, kind=$kind, outcome=$outcome)"

    enum class Kind {
        /** The engine decided, or a stage made the decision indeterminate (memberships, snapshot, deadline). */
        DECIDED,

        /** The request could not be interpreted without ambiguity: 400. */
        BAD_REQUEST,

        /** The authenticator was unavailable: 503 before any decision. */
        AUTHENTICATION_UNAVAILABLE,

        /** The caller went away: no outcome was written. */
        CANCELLED,

        /** An exception nothing modelled: a defect, 500. */
        ERROR,
    }
}

/** What was answered: the status, and why, in more detail than the response body (§6.2). */
class OutcomeSummary internal constructor(
    val status: Int,
    val proceed: Boolean,
    /** The problem written; null when the request proceeds or was cancelled. */
    val problem: ProblemType?,
    val reason: Reason,
    /** Why the request is indeterminate, when [reason] is [Reason.INDETERMINATE]. */
    val cause: Cause? = null,
) {
    enum class Reason {
        PERMIT,
        EXPLICIT_DENY,
        IMPLICIT_DENY,
        SCOPE_REJECTED,
        INDETERMINATE,
        AUTHENTICATOR_UNAVAILABLE,
        DEADLINE_EXCEEDED,
        INVALID_REQUEST,
        CANCELLED,
        ERROR,
    }

    override fun toString(): String = "Outcome($status, $reason)"

    internal companion object {
        /** A cancelled request has no outcome; this summary only says so. */
        val CANCELLED = OutcomeSummary(0, false, null, Reason.CANCELLED)
    }
}

/** Who asked: the principal as authenticated, or why its credentials were rejected. */
class SubjectSummary internal constructor(
    val tenant: String,
    val id: String,
    val authenticated: Boolean,
    /** How it authenticated (`token`, `api-key`, ...); null when anonymous. */
    val method: String?,
    /** Why the credentials were rejected; null when they were not. */
    val rejected: AuthnResult.RejectReason?,
) {
    override fun toString(): String = "Subject($tenant/$id, authenticated=$authenticated)"
}

/** The request, without headers, query or body. */
class RequestSummary internal constructor(
    val method: String,
    /** The normalized path; the raw one when it could not be normalized. */
    val path: String,
    val remoteIp: String,
    /** Empty when the request names no device. */
    val deviceId: String,
)

/**
 * The trace the request belongs to, as the adapter's bridge captured it (RFC 0006 §7.5): put into the coroutine
 * context by a [BridgeContextContributor], read by the pipeline for events and problem bodies.
 */
class TraceContext(val traceId: String, val spanId: String, val sampled: Boolean) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceContext>

    override fun toString(): String = "TraceContext($traceId/$spanId)"
}

/**
 * What a shadow listener needs to evaluate the request again (RFC 0006 §9.5.4): the request, the view and the external
 * attributes already fetched. It keeps the view reachable, so the pipeline creates one only when a shadow listener
 * sampled the request ([sampledBy]).
 */
class ReplayHandle internal constructor(
    internal val request: FlatRequest,
    internal val view: SnapshotView,
    internal val sampledBy: Set<String>,
)
