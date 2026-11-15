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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.OutcomeSummary
import me.ahoo.cosec.runtime.http.Basis
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.Unavailability

/** How an outcome reads in a [DecisionEvent] (RFC 0006 §9.2): its kind, and its summary without the problem body. */
internal object DecisionEvents {
    fun kind(basis: Basis?): DecisionEvent.Kind = when (basis) {
        null -> DecisionEvent.Kind.CANCELLED
        is Basis.InvalidRequest -> DecisionEvent.Kind.BAD_REQUEST
        is Basis.Error -> DecisionEvent.Kind.ERROR
        is Basis.Unavailable ->
            if (basis.reason is Unavailability.AuthenticatorUnavailable) {
                DecisionEvent.Kind.AUTHENTICATION_UNAVAILABLE
            } else {
                DecisionEvent.Kind.DECIDED
            }
        is Basis.Decided, is Basis.RateLimited, is Basis.Asserted -> DecisionEvent.Kind.DECIDED
    }

    /** [outcome]'s summary; [OutcomeSummary.CANCELLED] when the caller went away and there is none. */
    fun summary(outcome: Outcome?): OutcomeSummary {
        if (outcome == null) {
            return OutcomeSummary.CANCELLED
        }
        val reason = when (val basis = outcome.basis) {
            is Basis.InvalidRequest -> OutcomeSummary.Reason.INVALID_REQUEST
            is Basis.Error -> OutcomeSummary.Reason.ERROR
            is Basis.Unavailable -> when (basis.reason) {
                is Unavailability.Indeterminate -> OutcomeSummary.Reason.INDETERMINATE
                is Unavailability.AuthenticatorUnavailable -> OutcomeSummary.Reason.AUTHENTICATOR_UNAVAILABLE
                Unavailability.DeadlineExceeded -> OutcomeSummary.Reason.DEADLINE_EXCEEDED
            }
            is Basis.Decided -> reason(basis.decision)
            is Basis.RateLimited -> OutcomeSummary.Reason.TOO_MANY_REQUESTS
            // The gate of a service behind a gateway makes no events; its outcomes proceed.
            is Basis.Asserted -> OutcomeSummary.Reason.PERMIT
        }
        val cause = when (val basis = outcome.basis) {
            is Basis.Unavailable -> (basis.reason as? Unavailability.Indeterminate)?.cause
            else -> null
        }
        return OutcomeSummary(outcome.status, outcome.proceed, outcome.problem?.type, reason, cause)
    }

    private fun reason(decision: Decision): OutcomeSummary.Reason = when (decision) {
        is Decision.Permit -> OutcomeSummary.Reason.PERMIT
        is Decision.Deny -> when (decision.by) {
            null -> OutcomeSummary.Reason.IMPLICIT_DENY
            is StatementRef.ScopeRejected -> OutcomeSummary.Reason.SCOPE_REJECTED
            is StatementRef.Policy -> OutcomeSummary.Reason.EXPLICIT_DENY
            is StatementRef.Boundary -> OutcomeSummary.Reason.BOUNDARY
        }
        is Decision.Indeterminate -> OutcomeSummary.Reason.INDETERMINATE
    }
}
