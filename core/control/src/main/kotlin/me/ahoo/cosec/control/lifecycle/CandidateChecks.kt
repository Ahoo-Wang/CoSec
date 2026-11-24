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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock

/** A change's candidate on a head, and the checks and grant analysis it goes through there (RFC 0007 §6.2, §7.4). */
internal class CandidateChecks(
    private val checks: ChangeChecks,
    private val context: CurrentDocuments,
    private val authority: ChangeAuthority,
    private val clock: Clock,
    private val options: ChangeOptions,
    private val lockout: LockoutCheck,
) {
    /** That [operator] may make [change]'s operations on its document's current head ([ChangeAuthority.authorize]). */
    suspend fun authorize(operator: Operator, change: ChangeRequest) {
        val head = context.head(change.key)?.document
        val breakGlass = BreakGlassRules.touches(change.operations, head, clock.instant())
        authority.authorize(operator, change.key, change.operations, change.kind, breakGlass, head)
    }

    /**
     * Runs the checks of [change] on [head] and returns it `CHECKED` or `DRAFT`, with what they found; a clean
     * candidate is analyzed for what [operator] grants with it, and carries the escalations.
     */
    suspend fun checked(change: ChangeRequest, head: Head?, operator: Operator): ChangeRequest {
        val now = clock.instant()
        val (built, applyFindings) = applied(change, head?.document)
        built
            ?: return change.copy(
                state = ChangeState.DRAFT,
                findings = applyFindings,
                candidate = null,
                checkedWith = emptyMap(),
                escalations = emptyList(),
                updatedAt = now
            )
        val recording = RecordingCurrentDocuments(context)
        val checkedResult = checks.check(
            change.key,
            built,
            change.kind,
            change.operations,
            head?.document,
            recording
        )
        // What is analysed, approved and published is what the data plane reads: the decoded document (R2C-34). One that
        // does not decode has findings and goes no further.
        val candidate = checkedResult.candidate ?: built
        val breakGlass = BreakGlassRules.findings(
            change.kind,
            change.operations,
            head?.document,
            candidate,
            now,
            options.breakGlassMaxDuration
        )
        val checked = CheckResult(applyFindings + breakGlass + checkedResult.findings, checkedResult.checkedWith)
        // UC-6: a clean candidate that would keep [operator] out of the control plane says so; publishing it needs an
        // acknowledgement ([Publishing.publish]).
        val lockedOut = if (checked.clean) lockedOut(operator, change.key, candidate) else null
        val result = lockedOut?.let { CheckResult(checked.findings + it, checked.checkedWith) } ?: checked
        val escalations = if (result.clean) {
            authority.escalations(operator, change.key, change.operations, head?.document, candidate, recording, now)
        } else {
            emptyList()
        }
        return change.copy(
            state = if (result.clean) ChangeState.CHECKED else ChangeState.DRAFT,
            findings = result.findings,
            candidate = if (result.clean) CandidateDigest.of(candidate) else null,
            checkedWith = result.checkedWith + recording.read.filterKeys { it != change.key && it !in result.checkedWith },
            checkedApps = recording.apps,
            escalations = escalations,
            updatedAt = now,
        )
    }

    /**
     * The candidate [change]'s operations make of [base], with the `nomatch` confirmations the control plane writes
     * (RFC 0007 §9.5), and what kept them from applying or left a DENY unconfirmed.
     */
    fun applied(change: ChangeRequest, base: PolicyDocument?): Pair<PolicyDocument?, List<Finding>> {
        val applied = Operations.apply(change.key, base, change.operations)
        val candidate = applied.candidate ?: return null to applied.findings
        val (confirmed, findings) = NomatchConfirmations.apply(
            candidate,
            base,
            change.confirmations,
            change.author,
            change.id
        )
        return confirmed to applied.findings + findings
    }

    private suspend fun lockedOut(operator: Operator, key: SnapshotKey, candidate: PolicyDocument): Finding? {
        val lockout = lockout.check(operator, key, candidate) ?: return null
        // A statement is named only to an operator who may read the document its policy is defined in (CTL-51, DR1-F-13).
        val named = lockout.namedIn.all { authority.holds(operator, it, ControlActions.READ) }
        val reason = if (named) lockout.reason else lockout.unnamed
        val message = "after this change ${operator.actor.principal.value}@${operator.actor.tenant.value} could not reach the control plane: $reason. Publishing " +
            "it needs the acknowledgement \"lockout\": true; the way back from a lock-out is cosec-migrate restore"
        return Finding(LockoutCheck.CHECK, "$", message, UNPROCESSABLE, Severity.WARNING)
    }
}
