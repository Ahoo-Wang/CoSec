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

package me.ahoo.cosec.control.grant

/**
 * How many comparisons one grant analysis may make (R2C-25, R2C-26, `CTL-49`): each previous statement a changed one is
 * compared with, each held ALLOW and DENY a fragment is checked against. One budget is spent in the order the analysis
 * runs, so every instance and every re-check of the same change spends it alike and stops at the same comparison.
 *
 * What the budget does not reach is not known, and what is not known needs an approval: a statement still being
 * compared when it runs out is taken as granted, and every fragment from then on is
 * [UnknownReason.TOO_LARGE]. Nothing is ever contained on a comparison the budget did not pay for.
 */
internal class ComparisonBudget(private val limit: Long = MAX_COMPARISONS) {
    /** The comparisons made so far, past the limit included. */
    var spent: Long = 0
        private set

    /** Whether the comparisons run out before the analysis did. */
    val exhausted: Boolean get() = spent > limit

    /** Spends one comparison: false once there is none left to spend. */
    fun spend(): Boolean = ++spent <= limit

    companion object {
        /**
         * 66 times the largest legitimate change measured after the indexes (`SaasAppChangeMeasurement.largestLegitimate`,
         * 2026-11-20): a tenant administrator holding a role of every permission of twelve apps grants staff a role of
         * each, 1,200 fragments and 60,600 comparisons (an app change of 100 permissions makes 100). An analysis that
         * spends all of it on the dearest comparison measured (a held ALLOW of the same action and another resource)
         * took 1.4 s of CPU.
         */
        const val MAX_COMPARISONS: Long = 4_000_000

        /**
         * The comparisons the largest legitimate change makes per fragment it grants: 60,600 for 1,200, rounded down
         * (`SaasAppChangeMeasurement.largestLegitimate`).
         */
        const val COMPARISONS_PER_FRAGMENT: Long = 50

        /**
         * The fragments one analysis splits a change into (DR1-E-1): as many as [MAX_COMPARISONS] can check at the rate
         * of the largest legitimate change, 80,000, 66 times its 1,200. A change of more is too large to analyse before
         * any is split, as one finding: a fragment the grantor holds nothing for costs no comparison, so the comparison
         * budget alone bounds neither how many are split nor how many are recorded.
         */
        const val MAX_FRAGMENTS: Long = MAX_COMPARISONS / COMPARISONS_PER_FRAGMENT
    }
}
