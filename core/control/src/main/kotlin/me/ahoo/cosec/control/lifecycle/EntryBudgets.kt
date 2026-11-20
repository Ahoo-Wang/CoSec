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
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.DocumentBudgets
import me.ahoo.cosec.document.PolicyDocument

/**
 * `CTL-40` (R1B-4): the entry budgets of the platform's `limits` ([DocumentBudgets]), held to every candidate the
 * control plane checks: a proposal, an `apply`, a rollback, a rebase, and so what is published. For every budget, the
 * candidate's count must be at most the larger of the limit and the count of the head it replaces: a document already
 * over a budget may change, and shrink, but nothing may raise a count that is over its budget. A count still over
 * after a change is a warning naming it.
 *
 * A rollback is held to the same rule against the current head: it may restore a revision over a budget only as far as
 * the head already is, so it is never the one change that cannot shrink a document back. Neither the compiler nor a
 * host enforces a budget: a stored document over one keeps being served.
 */
internal object EntryBudgets {
    fun check(
        base: PolicyDocument?,
        candidate: PolicyDocument,
        budgets: DocumentBudgets,
        findings: MutableList<Finding>
    ) {
        for (entry in DocumentBudgets.Entry.entries) {
            val limit = entry.limit(budgets)
            val count = entry.count(candidate)
            if (count <= limit) {
                continue
            }
            val before = base?.let(entry::count) ?: 0
            findings += if (count > before) {
                Finding(
                    "V2",
                    entry.path,
                    "the document would hold $count ${entry.what}, more than the platform's limits.${entry.member} " +
                        "($limit), and more than the $before it holds now: remove some, or have the platform raise " +
                        "limits.${entry.member} (CTL-40)",
                    UNPROCESSABLE,
                )
            } else {
                Finding(
                    "V2",
                    entry.path,
                    "the document holds $count ${entry.what}, more than the platform's limits.${entry.member} " +
                        "($limit): it is served, but no change may add to them until they are within it (CTL-40)",
                    UNPROCESSABLE,
                    Severity.WARNING,
                )
            }
        }
    }
}
