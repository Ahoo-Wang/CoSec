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

/** Which conflict a `409` of the control API is (`conflict`, `CTL-80`): read again and retry, wait, or stop. A value this version does not know keeps its text ([ClosedValue]). */
@ExperimentalCoSecApi
class ConflictKind private constructor(text: String, isUnknown: Boolean) : ClosedValue(text, isUnknown) {
    @ExperimentalCoSecApi
    companion object {
        val BASE_MOVED: ConflictKind = ConflictKind("BASE_MOVED", false)
        val SLOT_HELD: ConflictKind = ConflictKind("SLOT_HELD", false)
        val STATE: ConflictKind = ConflictKind("STATE", false)
        val CANDIDATE_MOVED: ConflictKind = ConflictKind("CANDIDATE_MOVED", false)
        val WINDOW_OPEN: ConflictKind = ConflictKind("WINDOW_OPEN", false)
        val WINDOW_BLOCKED: ConflictKind = ConflictKind("WINDOW_BLOCKED", false)
        val CONTENDED: ConflictKind = ConflictKind("CONTENDED", false)
        val EXISTS: ConflictKind = ConflictKind("EXISTS", false)

        private val SET = ClosedSet(listOf(BASE_MOVED, SLOT_HELD, STATE, CANDIDATE_MOVED, WINDOW_OPEN, WINDOW_BLOCKED, CONTENDED, EXISTS)) { ConflictKind(it, true) }

        /** Every value this version knows. */
        val KNOWN: List<ConflictKind> get() = SET.known

        /** The value [text] names: a known one, or an unknown one that keeps the text. */
        fun of(text: String): ConflictKind = SET.of(text)
    }
}
