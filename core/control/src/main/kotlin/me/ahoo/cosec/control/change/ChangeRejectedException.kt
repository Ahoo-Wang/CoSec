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
 * that holds the slot, [heldBy], where the refusal knows it: use [slotHeld].
 */
@InternalCoSecApi
class ChangeRejectedException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
    /** Which conflict a `409` is; null for any other status. */
    val conflict: ConflictKind? = null,
    /** The change that holds the slot a [ConflictKind.SLOT_HELD] wanted; null for any other refusal. */
    val heldBy: ChangeId? = null,
) : RuntimeException(message, cause) {
    init {
        require((status == ControlStatus.CONFLICT) == (conflict != null)) {
            "A 409 of the control API names its conflict, and only a 409 does (CTL-80): status $status, conflict $conflict."
        }
        require(heldBy == null || conflict == ConflictKind.SLOT_HELD) {
            "Only a SLOT_HELD names the change that holds the slot (CTL-80): conflict $conflict."
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
    }
}
