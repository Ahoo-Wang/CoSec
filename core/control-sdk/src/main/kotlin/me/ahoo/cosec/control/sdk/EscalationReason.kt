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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/** Why what a change grants waits for a person (`escalationReason`, RFC 0007 §7.4). A value this version does not know keeps its text ([ClosedValue]). */
@ExperimentalCoSecApi
class EscalationReason private constructor(text: String, isUnknown: Boolean) : ClosedValue(text, isUnknown) {
    @ExperimentalCoSecApi
    companion object {
        val CROSS_TENANT: EscalationReason = EscalationReason("CROSS_TENANT", false)
        val NOT_COVERED: EscalationReason = EscalationReason("NOT_COVERED", false)
        val DENY_OVERLAP: EscalationReason = EscalationReason("DENY_OVERLAP", false)
        val UNRESOLVED: EscalationReason = EscalationReason("UNRESOLVED", false)
        val UNREADABLE: EscalationReason = EscalationReason("UNREADABLE", false)
        val ANONYMOUS: EscalationReason = EscalationReason("ANONYMOUS", false)
        val TOO_LARGE: EscalationReason = EscalationReason("TOO_LARGE", false)
        val NOT_ANALYZED: EscalationReason = EscalationReason("NOT_ANALYZED", false)
        val BREAK_GLASS: EscalationReason = EscalationReason("BREAK_GLASS", false)
        val TENANT_BINDINGS: EscalationReason = EscalationReason("TENANT_BINDINGS", false)
        val DETECTION_TIMED_OUT: EscalationReason = EscalationReason("DETECTION_TIMED_OUT", false)
        /**
         * The role class (`CTL-79`): recorded in place of the escalations it lifted from a roles-only change of a
         * tenant's document by a holder of `cosec:manage-roles`. Informational: it never holds a change.
         */
        val ROLE_CLASS: EscalationReason = EscalationReason("ROLE_CLASS", false)
        val UNKNOWN: EscalationReason = EscalationReason("UNKNOWN", false)

        private val SET = ClosedSet(
            listOf(CROSS_TENANT, NOT_COVERED, DENY_OVERLAP, UNRESOLVED, UNREADABLE, ANONYMOUS, TOO_LARGE, NOT_ANALYZED, BREAK_GLASS, TENANT_BINDINGS, DETECTION_TIMED_OUT, ROLE_CLASS, UNKNOWN)
        ) { EscalationReason(it, true) }

        /** Every value this version knows. */
        val KNOWN: List<EscalationReason> get() = SET.known

        /** The value [text] names: a known one, or an unknown one that keeps the text. */
        fun of(text: String): EscalationReason = SET.of(text)
    }
}
