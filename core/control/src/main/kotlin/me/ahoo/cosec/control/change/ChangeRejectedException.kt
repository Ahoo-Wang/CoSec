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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * A change operation the control API refuses, with the HTTP [status] it answers (RFC 0007 §10.2). A `409` names its
 * [conflict], and only a `409` does (`CTL-80`): use [conflict] to make one. A [ConflictKind.SLOT_HELD] names the change
 * that holds the slot, [heldBy], where the refusal knows it: use [slotHeld]; a [ConflictKind.OPEN_LIMIT] the oldest open
 * change of the document: use [openLimit].
 */
@InternalCoSecApi
class ChangeRejectedException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
    /** Which conflict a `409` is; null for any other status. */
    val conflict: ConflictKind? = null,
    /** The change a [ConflictKind.SLOT_HELD] or an [ConflictKind.OPEN_LIMIT] names; null for any other refusal. */
    val heldBy: ChangeId? = null,
) : RuntimeException(message, cause) {
    init {
        require((status == ControlStatus.CONFLICT) == (conflict != null)) {
            "A 409 of the control API names its conflict, and only a 409 does (CTL-80): status $status, conflict $conflict."
        }
        require(heldBy == null || conflict in HOLDING) {
            "Only a SLOT_HELD or an OPEN_LIMIT names a change (CTL-80, CTL-83): conflict $conflict."
        }
    }

    @InternalCoSecApi
    companion object {
        /** A `409` of [kind]. */
        fun conflict(kind: ConflictKind, message: String, cause: Throwable? = null): ChangeRejectedException =
            ChangeRejectedException(ControlStatus.CONFLICT, message, cause, kind)

        /** A `409` [ConflictKind.SLOT_HELD]: the slot is held by change [heldBy], which [message] names too. */
        fun slotHeld(heldBy: ChangeId, message: String, cause: Throwable? = null): ChangeRejectedException =
            ChangeRejectedException(ControlStatus.CONFLICT, message, cause, ConflictKind.SLOT_HELD, heldBy)

        /** A `409` [ConflictKind.OPEN_LIMIT]: the document holds as many open changes as it may, the oldest [oldest]. */
        fun openLimit(oldest: ChangeId, message: String): ChangeRejectedException =
            ChangeRejectedException(ControlStatus.CONFLICT, message, null, ConflictKind.OPEN_LIMIT, oldest)

        /** The conflicts that name a change in `heldBy`. */
        private val HOLDING = setOf(ConflictKind.SLOT_HELD, ConflictKind.OPEN_LIMIT)
    }
}
