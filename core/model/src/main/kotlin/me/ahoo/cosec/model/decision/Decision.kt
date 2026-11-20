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

package me.ahoo.cosec.model.decision

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId

/**
 * What the engine decided. The values are plain final classes with `equals`, `hashCode` and `toString` written out, not data
 * classes, as `RateLimitResult`, `VerifiedToken` and `ApiKey` are (RFC 0010 C4): an application reads them (`when`,
 * `is`), never makes one, and a field can be added later without breaking a caller. There is no `copy` and no `componentN`.
 */
sealed interface Decision {
    /**
     * [obligations] are what the pipeline must do before the request proceeds (RFC 0006 §8.2): only a permit has any,
     * so a denied request never spends a rate limit permit (P15).
     */
    class Permit(val by: StatementRef.Policy, val obligations: List<Obligation> = emptyList()) : Decision {
        override fun equals(other: Any?): Boolean =
            this === other || other is Permit &&
            by == other.by &&
            obligations == other.obligations

        override fun hashCode(): Int = 31 * by.hashCode() + obligations.hashCode()

        override fun toString(): String = "Permit(by=$by, obligations=$obligations)"
    }

    /** [by] is null for an implicit deny: no ALLOW matched. */
    class Deny(val by: StatementRef?) : Decision {
        override fun equals(other: Any?): Boolean =
            this === other || other is Deny &&
            by == other.by

        override fun hashCode(): Int = (by?.hashCode() ?: 0)

        override fun toString(): String = "Deny(by=$by)"
    }

    /** No decision could be made, and no DENY matched. Callers deny. */
    class Indeterminate(val cause: Cause) : Decision {
        override fun equals(other: Any?): Boolean =
            this === other || other is Indeterminate &&
            cause == other.cause

        override fun hashCode(): Int = cause.hashCode()

        override fun toString(): String = "Indeterminate(cause=$cause)"
    }
}

/**
 * Why a decision is [Decision.Indeterminate]. The engine finds some of them itself (missing
 * policies, documents not loaded), so not every cause carries an exception: the adapter builds the one it reports.
 *
 * A set that grows: a minor release may add a member, such as the failure of a new port (RFC 0010 Q6), so a `when`
 * over it needs an `else`, and a decision listener never matches it exhaustively.
 */
sealed interface Cause {
    /**
     * A matcher threw before the decision: a DENY statement at [at], or, when [guard] is true, the condition of [at]'s
     * policy, reached through a DENY statement. A failing ALLOW never makes a decision indeterminate (RFC 0004 C1a).
     */
    class MatcherFailed(val at: StatementRef.Policy, val guard: Boolean, val error: Throwable) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is MatcherFailed &&
            at == other.at &&
            guard == other.guard &&
            error == other.error

        override fun hashCode(): Int = 31 * (31 * at.hashCode() + guard.hashCode()) + error.hashCode()

        override fun toString(): String = "MatcherFailed(at=$at, guard=$guard, error=$error)"
    }

    /**
     * Policies a request needs are not in the snapshot: attachments that are missing or not attachable (RFC 0004 §4.4),
     * and bindings of an app or tenant document whose platform policy is gone (§7.2).
     */
    class MissingPolicies(val policyIds: Set<PolicyId>) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is MissingPolicies &&
            policyIds == other.policyIds

        override fun hashCode(): Int = policyIds.hashCode()

        override fun toString(): String = "MissingPolicies(policyIds=$policyIds)"
    }

    /**
     * Policies a request needs that both the tenant document and the platform define (RFC 0007 §3.1 N2, Q15). Which
     * one was meant cannot be told, and preferring the tenant's would let a tenant replace a platform DENY, so the
     * request fails closed. Until compiled content can be compared, any shared id is ambiguous.
     */
    class AmbiguousPolicy(val policyIds: Set<PolicyId>) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is AmbiguousPolicy &&
            policyIds == other.policyIds

        override fun hashCode(): Int = policyIds.hashCode()

        override fun toString(): String = "AmbiguousPolicy(policyIds=$policyIds)"
    }

    /**
     * A snapshot document could not be read: the store failed or timed out, a document is not confirmed fresh enough, or
     * one compiled against inputs that changed has not been compiled again yet. A document that is stored and does not
     * compile is [DocumentInvalid].
     */
    class SourceUnavailable(val document: DocumentKind, val error: Throwable) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is SourceUnavailable &&
            document == other.document &&
            error == other.error

        override fun hashCode(): Int = 31 * document.hashCode() + error.hashCode()

        override fun toString(): String = "SourceUnavailable(document=$document, error=$error)"
    }

    /**
     * A snapshot document the request needs is stored but does not compile on this instance: written by a newer CoSec
     * during a rolling upgrade, written to the store directly, or compiled against platform limits and attributes it
     * breaks. [revision] is the stored revision, which the decision event's view version also carries; [error] names
     * what does not compile. Every request that needs the document fails closed, 503, because this instance cannot
     * decide with it, not because a retry would help: publishing a revision that compiles ends it.
     */
    class DocumentInvalid(val document: DocumentKind, val revision: Long, val error: Throwable) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is DocumentInvalid &&
            document == other.document &&
            revision == other.revision &&
            error == other.error

        override fun hashCode(): Int = 31 * (31 * document.hashCode() + revision.hashCode()) + error.hashCode()

        override fun toString(): String = "DocumentInvalid(document=$document, revision=$revision, error=$error)"
    }

    /** A document the request needs was not given to the engine. */
    data class NotLoaded(val document: DocumentKind) : Cause

    /**
     * A statement of the platform boundary [boundary] failed for a request an ALLOW permitted, and none matched
     * (RFC 0007 §7.6): whether the request is within the boundary cannot be told.
     */
    class BoundaryUnavailable(val boundary: PolicyId, val error: Throwable) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is BoundaryUnavailable &&
            boundary == other.boundary &&
            error == other.error

        override fun hashCode(): Int = 31 * boundary.hashCode() + error.hashCode()

        override fun toString(): String = "BoundaryUnavailable(boundary=$boundary, error=$error)"
    }

    /**
     * External attributes a statement needed could not be fetched (RFC 0006 §5.4.3): their provider failed, timed out,
     * or they were not in the attribute plan; or `environment.ip`, when the host reported no address (`PIP-17`). [at] is the first statement that needed them, or null when no statement
     * did, such as an unreadable blacklist (§5.4.4).
     */
    class AttributeUnavailable(val attributes: Set<String>, val at: StatementRef.Policy?) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is AttributeUnavailable &&
            attributes == other.attributes &&
            at == other.at

        override fun hashCode(): Int = 31 * attributes.hashCode() + (at?.hashCode() ?: 0)

        override fun toString(): String = "AttributeUnavailable(attributes=$attributes, at=$at)"
    }

    /**
     * A check in code (`AuthorizationChecker.decide`) threw: [error] is what failed. Never a request's cause: only a
     * check answers it, and a check reaches no decision listener, audit, metric or span (UA-16).
     */
    class CheckFailed(val error: Throwable) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is CheckFailed &&
            error == other.error

        override fun hashCode(): Int = error.hashCode()

        override fun toString(): String = "CheckFailed(error=$error)"
    }

    /** The principal's memberships could not be resolved (RFC 0004 §4.5.2). */
    class MembershipUnavailable(val error: Throwable) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is MembershipUnavailable &&
            error == other.error

        override fun hashCode(): Int = error.hashCode()

        override fun toString(): String = "MembershipUnavailable(error=$error)"
    }

    /**
     * A permit could not be written to the REQUIRED decision listeners named in [listeners] (RFC 0006 §9.4): one failed
     * or timed out, or, for a break-glass permit, none is configured or none succeeded.
     */
    class RequiredListenerFailed(val listeners: List<String>) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is RequiredListenerFailed &&
            listeners == other.listeners

        override fun hashCode(): Int = listeners.hashCode()

        override fun toString(): String = "RequiredListenerFailed(listeners=$listeners)"
    }

    /**
     * The rate limiter could not count rule [ruleId], whose `strictFailure` makes that a refusal (RFC 0006 §8.3, R3):
     * its backend failed or timed out, or the rule's bucket could not be computed.
     */
    class RateLimiterUnavailable(val ruleId: String) : Cause {
        override fun equals(other: Any?): Boolean =
            this === other || other is RateLimiterUnavailable &&
            ruleId == other.ruleId

        override fun hashCode(): Int = ruleId.hashCode()

        override fun toString(): String = "RateLimiterUnavailable(ruleId=$ruleId)"
    }
}

/**
 * What a permit obliges the pipeline to do before the request proceeds (RFC 0006 §8.2).
 * A set that grows: a minor release may add a member (RFC 0010 Q6), so a `when` over it needs an `else`, and an
 * implementation of a listener or a store never matches it exhaustively.
 */
sealed interface Obligation {
    /**
     * Spend one permit of [limit] from [bucket] of rule [ruleId]. [bucket] is null when the rule's key could not be read
     * (an external attribute that could not be fetched): [strictFailure] then decides, as for a failed backend.
     */
    class RateLimit(val ruleId: String, val bucket: String?, val limit: RateLimitSpec, val strictFailure: Boolean) : Obligation {
        override fun equals(other: Any?): Boolean =
            this === other || other is RateLimit &&
            ruleId == other.ruleId &&
            bucket == other.bucket &&
            limit == other.limit &&
            strictFailure == other.strictFailure

        override fun hashCode(): Int = 31 * (31 * (31 * ruleId.hashCode() + (bucket?.hashCode() ?: 0)) + limit.hashCode()) + strictFailure.hashCode()

        override fun toString(): String = "RateLimit(ruleId=$ruleId, bucket=$bucket, limit=$limit, strictFailure=$strictFailure)"
    }
}

/** A sliding-window quota: at most [permitsPerSecond] × [windowSeconds] permits per window of [windowSeconds]. */
data class RateLimitSpec(val permitsPerSecond: Double, val windowSeconds: Long = 1) {
    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive, not $permitsPerSecond." }
        require(windowSeconds in 1..MAX_WINDOW_SECONDS) {
            "windowSeconds must be between 1 and $MAX_WINDOW_SECONDS (a day), not $windowSeconds."
        }
        // The limiters count in a window of windowSeconds * 1000 milliseconds and report the quota as a Long.
        require(fitsInLong(permitsPerSecond, windowSeconds)) {
            "permitsPerSecond x windowSeconds must be a finite number within a Long, not $permitsPerSecond x $windowSeconds."
        }
    }

    /** The permits one window holds: [permitsPerSecond] × [windowSeconds]. */
    val limitPerWindow: Double
        get() = permitsPerSecond * windowSeconds

    /** The window in milliseconds, which the limiters count in. */
    val windowMillis: Long
        get() = windowSeconds * MILLIS_PER_SECOND

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * The longest window: a day (round-2 review M3 of the backends). A counter key lives two windows in a Redis that
         * must not evict, one key per bucket value, so a long window keyed by something a client chooses is memory the
         * client controls; and its milliseconds never overflow. A bound can be raised after GA, never lowered.
         */
        const val MAX_WINDOW_SECONDS: Long = 86_400L

        /** Whether [permitsPerSecond] x [windowSeconds] is finite and at most [Long.MAX_VALUE]. For CoSec's own types. */
        @InternalCoSecApi
        fun fitsInLong(permitsPerSecond: Double, windowSeconds: Long): Boolean {
            val perWindow = permitsPerSecond * windowSeconds
            return perWindow.isFinite() && perWindow <= Long.MAX_VALUE.toDouble()
        }
    }
}
