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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.grant.ComparisonBudget
import me.ahoo.cosec.control.grant.GrantAnalysis
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.UnknownReason
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** What a grant analysis could not prove contained, as the escalations a change records (RFC 0007 §7.4, §7.5). */
internal object UnknownEscalations {
    /**
     * The escalations of [analysis]'s unknown fragments. What is too large to analyse is one escalation, never one per
     * fragment (`CTL-49`, DR1-E-1): a change too large to split, or every fragment past the comparison budget.
     */
    fun of(key: SnapshotKey, analysis: GrantAnalysis): List<Escalation> {
        if (analysis.tooLarge) {
            return listOf(
                Escalation(
                    "change of $key",
                    Escalation.Reason.TOO_LARGE,
                    "it grants more than ${ComparisonBudget.MAX_FRAGMENTS} fragments, too many to analyse: split the change",
                ),
            )
        }
        val unknown = ArrayList<Escalation>()
        var pastBudget = 0
        for ((fragment, verdict) in analysis.verdicts) {
            if (verdict !is GrantVerdict.Unknown) continue
            if (verdict.reason == UnknownReason.TOO_LARGE) {
                pastBudget++
            } else {
                unknown += Escalation(fragment.toString(), Escalation.Reason.of(verdict.reason.name), verdict.toString())
            }
        }
        if (pastBudget > 0) {
            unknown += Escalation(
                "$pastBudget fragments of $key",
                Escalation.Reason.TOO_LARGE,
                "past the analysis's ${ComparisonBudget.MAX_COMPARISONS} comparisons, so not known: split the change",
            )
        }
        return unknown
    }
}
