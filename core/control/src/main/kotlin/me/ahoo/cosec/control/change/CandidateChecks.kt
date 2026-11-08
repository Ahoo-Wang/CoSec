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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.runtime.document.PolicyDocument
import java.time.Clock

/** A change's candidate on a head, and the checks and grant analysis it goes through there (RFC 0007 §6.2, §7.4). */
internal class CandidateChecks(
    private val checks: ChangeChecks,
    private val context: ChangeChecks.Context,
    private val authority: ChangeAuthority,
    private val clock: Clock,
    private val options: ChangeOptions,
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
        val (candidate, applyFindings) = applied(change, head?.document)
        candidate
            ?: return change.copy(
                state = ChangeState.DRAFT,
                findings = applyFindings,
                candidate = null,
                checkedWith = emptyMap(),
                escalations = emptyList(),
                updatedAt = now
            )
        val recording = RecordingContext(context)
        val checkedResult = checks.check(
            change.key,
            candidate,
            change.kind,
            change.operations,
            head?.document,
            recording
        )
        val breakGlass = BreakGlassRules.findings(
            change.kind,
            change.operations,
            head?.document,
            candidate,
            now,
            options.breakGlassMaxDuration
        )
        val result = CheckResult(applyFindings + breakGlass + checkedResult.findings, checkedResult.checkedWith)
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
}
