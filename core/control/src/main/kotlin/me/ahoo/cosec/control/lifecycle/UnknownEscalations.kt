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
import me.ahoo.cosec.control.grant.GrantFragment
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.UnknownReason
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.security.MessageDigest
import java.util.HexFormat

/**
 * What a grant analysis could not prove contained, as the escalations a change records (RFC 0007 §7.4, §7.5), bounded
 * whatever the change's shape (`CTL-88`, DR2-E-1): a change, its listing entry and each of its events carry the list.
 *
 * The unknown fragments of one statement, for one reason, are one escalation: its subject names the statement, the
 * reason, how many fragments it holds and the SHA-256 of them all, and its detail the first [EXAMPLES]. An approval
 * covers subjects, so it covers exactly the fragments it was shown: one more, one fewer or another one is a subject it
 * does not cover, and the change waits for an approval again, as it did when each fragment was an escalation of its
 * own. Past [MAX_GROUPS] statements, the fragments of the rest are one `TOO_LARGE` escalation, counted and digested the
 * same way, which needs an approval as anything not analyzed does: what is left out never lets a change through.
 */
internal object UnknownEscalations {
    /** The most statements whose unknown fragments are an escalation each; the rest are one. */
    const val MAX_GROUPS = 200

    /** The fragments an escalation's detail names. */
    const val EXAMPLES = 3

    /** The most characters of one example, and of a policy id in a subject: a resource pattern alone may be 2 KB. */
    const val MAX_TEXT = 256

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
        val groups = LinkedHashMap<Group, Tally>()
        val rest = Tally()
        val pastBudget = Tally()
        for ((fragment, verdict) in analysis.verdicts) {
            if (verdict !is GrantVerdict.Unknown) continue
            if (verdict.reason == UnknownReason.TOO_LARGE) {
                pastBudget.add(fragment, verdict)
                continue
            }
            val group = Group(fragment.policy, fragment.statement, Escalation.Reason.of(verdict.reason.name))
            val tally = groups[group] ?: (if (groups.size < MAX_GROUPS) Tally().also { groups[group] = it } else rest)
            tally.add(fragment, verdict)
        }
        val unknown = groups.mapTo(ArrayList()) { (group, tally) ->
            Escalation("${group.where()} ${group.reason}: ${tally.counted()}", group.reason, tally.examples())
        }
        if (rest.count > 0) {
            unknown += Escalation(
                "${rest.count} more fragments of $key ${rest.digest()}",
                Escalation.Reason.TOO_LARGE,
                "not proven contained, of statements past the first $MAX_GROUPS, so not shown: split the change",
            )
        }
        if (pastBudget.count > 0) {
            unknown += Escalation(
                "${pastBudget.count} fragments of $key ${pastBudget.digest()}",
                Escalation.Reason.TOO_LARGE,
                "past the analysis's ${ComparisonBudget.MAX_COMPARISONS} comparisons, so not known: split the change",
            )
        }
        return unknown
    }

    private fun clip(text: String): String {
        if (text.length <= MAX_TEXT) return text
        return "${text.take(MAX_TEXT)}… (${text.length} characters)"
    }

    /** A statement of a policy, and why its fragments are not known to be contained. */
    private data class Group(val policy: PolicyId, val statement: Int, val reason: Escalation.Reason) {
        fun where(): String {
            val id = clip(policy.value)
            return if (statement < 0) "$id (does not resolve)" else "$id[$statement]"
        }
    }

    /**
     * Fragments counted and digested as they come, in the analysis's order, which the documents fix: only the first
     * [EXAMPLES] are kept, so what one escalation holds does not grow with the fragments it stands for.
     */
    private class Tally {
        var count: Int = 0
            private set
        private val sha256 = MessageDigest.getInstance("SHA-256")
        private val examples = ArrayList<String>(EXAMPLES)

        fun add(fragment: GrantFragment, verdict: GrantVerdict.Unknown) {
            val text = fragment.toString()
            val bytes = text.encodeToByteArray()
            // Its length first, so that no two lists of fragments run together into the same bytes.
            sha256.update(ByteArray(Int.SIZE_BYTES) { (bytes.size shr (Byte.SIZE_BITS * it)).toByte() })
            sha256.update(bytes)
            if (examples.size < EXAMPLES) examples += clip("$text: $verdict")
            count++
        }

        fun digest(): String = HEX.formatHex(sha256.digest())

        fun counted(): String = "$count fragment${if (count == 1) "" else "s"} ${digest()}"

        fun examples(): String {
            val more = count - examples.size
            return examples.joinToString("; ") + if (more > 0) "; and $more more" else ""
        }
    }

    private val HEX: HexFormat = HexFormat.of()
}
