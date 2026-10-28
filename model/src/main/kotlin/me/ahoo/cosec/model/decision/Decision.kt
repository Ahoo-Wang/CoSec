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

package me.ahoo.cosec.model.decision

import me.ahoo.cosec.model.PolicyId

sealed interface Decision {
    /**
     * [obligations] are what the pipeline must do before the request proceeds (RFC 0006 §8.2): only a permit has any,
     * so a denied request never spends a rate limit permit (P15).
     */
    data class Permit(val by: StatementRef.Policy, val obligations: List<Obligation> = emptyList()) : Decision

    /** [by] is null for an implicit deny: no ALLOW matched. */
    data class Deny(val by: StatementRef?) : Decision

    /** No decision could be made, and no DENY matched. Callers deny. */
    data class Indeterminate(val cause: Cause) : Decision
}

/**
 * Why a decision is [Decision.Indeterminate]. The engine finds some of them itself (missing
 * policies, documents not loaded), so not every cause carries an exception: the adapter builds the one it reports.
 */
sealed interface Cause {
    /**
     * A matcher threw before the decision: a DENY statement at [at], or, when [guard] is true, the condition of [at]'s
     * policy, reached through a DENY statement. A failing ALLOW never makes a decision indeterminate (RFC 0004 C1a).
     */
    data class MatcherFailed(val at: StatementRef.Policy, val guard: Boolean, val error: Throwable) : Cause

    /**
     * Policies a request needs are not in the snapshot: attachments that are missing or not attachable (RFC 0004 §4.4),
     * and bindings of an app or tenant document whose platform policy is gone (§7.2).
     */
    data class MissingPolicies(val policyIds: Set<PolicyId>) : Cause

    /**
     * Policies a request needs that both the tenant document and the platform define (RFC 0007 §3.1 N2, Q15). Which
     * one was meant cannot be told, and preferring the tenant's would let a tenant replace a platform DENY, so the
     * request fails closed. Until compiled content can be compared, any shared id is ambiguous.
     */
    data class AmbiguousPolicy(val policyIds: Set<PolicyId>) : Cause

    /** Reading a snapshot document failed, for example a Redis timeout or a document that does not compile. */
    data class SourceUnavailable(val document: DocumentKind, val error: Throwable) : Cause

    /** A document the request needs was not given to the engine. */
    data class NotLoaded(val document: DocumentKind) : Cause

    /**
     * A statement of the platform boundary [boundary] failed for a request an ALLOW permitted, and none matched
     * (RFC 0007 §7.6): whether the request is within the boundary cannot be told.
     */
    data class BoundaryUnavailable(val boundary: PolicyId, val error: Throwable) : Cause

    /**
     * External attributes a statement needed could not be fetched (RFC 0006 §5.4.3): their provider failed, timed out,
     * or they were not in the attribute plan. [at] is the first statement that needed them, or null when no statement
     * did, such as an unreadable blacklist (§5.4.4).
     */
    data class AttributeUnavailable(val attributes: Set<String>, val at: StatementRef.Policy?) : Cause

    /** The principal's memberships could not be resolved (RFC 0004 §4.5.2). */
    data class MembershipUnavailable(val error: Throwable) : Cause

    /**
     * A permit could not be written to the REQUIRED decision listeners named in [listeners] (RFC 0006 §9.4): one failed
     * or timed out, or, for a break-glass permit, none is configured or none succeeded.
     */
    data class RequiredListenerFailed(val listeners: List<String>) : Cause

    /**
     * The rate limiter could not count rule [ruleId], whose `strictFailure` makes that a refusal (RFC 0006 §8.3, R3):
     * its backend failed or timed out, or the rule's bucket could not be computed.
     */
    data class RateLimiterUnavailable(val ruleId: String) : Cause
}

/** What a permit obliges the pipeline to do before the request proceeds (RFC 0006 §8.2). */
sealed interface Obligation {
    /**
     * Spend one permit of [limit] from [bucket] of rule [ruleId]. [bucket] is null when the rule's key could not be read
     * (an external attribute that could not be fetched): [strictFailure] then decides, as for a failed backend.
     */
    data class RateLimit(
        val ruleId: String,
        val bucket: String?,
        val limit: RateLimitSpec,
        val strictFailure: Boolean,
    ) : Obligation
}

/** A sliding-window quota: at most [permitsPerSecond] × [windowSeconds] permits per window of [windowSeconds]. */
data class RateLimitSpec(val permitsPerSecond: Double, val windowSeconds: Long = 1) {
    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive, not $permitsPerSecond." }
        require(windowSeconds >= 1) { "windowSeconds must be at least 1, not $windowSeconds." }
    }
}
