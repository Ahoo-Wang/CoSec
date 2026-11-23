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

/**
 * Which conflict a `409` of the control API is (`conflict`, `CTL-80`): read again and retry, wait, or stop. A value this
 * version does not know keeps its text ([ClosedValue]).
 */
@ExperimentalCoSecApi
class ConflictKind private constructor(text: String, isUnknown: Boolean) : ClosedValue(text, isUnknown) {
    @ExperimentalCoSecApi
    companion object {
        /** The document's head is not the change's base: read the head again, and propose again or rebase. */
        val BASE_MOVED: ConflictKind = ConflictKind("BASE_MOVED", false)

        /** Another change holds the document's staging slot, [ControlRefusedException.heldBy]: wait for it, or abandon it. */
        val SLOT_HELD: ConflictKind = ConflictKind("SLOT_HELD", false)

        /** The change's state does not admit the step: read the change again; the step is not for it now. */
        val STATE: ConflictKind = ConflictKind("STATE", false)

        /** The candidate named is not the change's: read the change again, and act on the candidate it has. */
        val CANDIDATE_MOVED: ConflictKind = ConflictKind("CANDIDATE_MOVED", false)

        /** The change's shadow window is still open: wait for it to close. */
        val WINDOW_OPEN: ConflictKind = ConflictKind("WINDOW_OPEN", false)

        /** The change's shadow window closed blocked: it never publishes; abandon it. */
        val WINDOW_BLOCKED: ConflictKind = ConflictKind("WINDOW_BLOCKED", false)

        /** Concurrent writes kept changing the record: the same call, made again, may succeed. */
        val CONTENDED: ConflictKind = ConflictKind("CONTENDED", false)

        /**
         * Something of that id already stands where the step needs something else: a change of that id, a binding that
         * break-glass would create, or a binding a break-glass revoke names that break-glass did not write. Stop: the id
         * names something else; choose another id, or act on what stands there through its own route.
         */
        val EXISTS: ConflictKind = ConflictKind("EXISTS", false)

        /**
         * The document holds as many open changes as it may (`CTL-83`), the oldest [ControlRefusedException.heldBy]:
         * abandon one, or wait for it to expire.
         */
        val OPEN_LIMIT: ConflictKind = ConflictKind("OPEN_LIMIT", false)

        private val SET = ClosedSet(
            listOf(BASE_MOVED, SLOT_HELD, STATE, CANDIDATE_MOVED, WINDOW_OPEN, WINDOW_BLOCKED, CONTENDED, EXISTS, OPEN_LIMIT)
        ) {
            ConflictKind(it, true)
        }

        /** Every value this version knows. */
        val KNOWN: List<ConflictKind> get() = SET.known

        /** The value [text] names: a known one, or an unknown one that keeps the text. */
        fun of(text: String): ConflictKind = SET.of(text)
    }
}
