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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ChangeState.ABANDONED
import me.ahoo.cosec.control.change.ChangeState.AWAITING_APPROVAL
import me.ahoo.cosec.control.change.ChangeState.CHECKED
import me.ahoo.cosec.control.change.ChangeState.CONFLICTED
import me.ahoo.cosec.control.change.ChangeState.DRAFT
import me.ahoo.cosec.control.change.ChangeState.EXPIRED
import me.ahoo.cosec.control.change.ChangeState.PUBLISHED
import me.ahoo.cosec.control.change.ChangeState.READY
import me.ahoo.cosec.control.change.ChangeState.REJECTED
import me.ahoo.cosec.control.change.ChangeState.SHADOWING

/**
 * The change flow's transition table (RFC 0007 §5.5, §6.1): each step a change can take, the states it moves a change
 * from and the states it leaves it in. A command refuses a change outside its row's [from] (409), and
 * [ChangeLifecycle.transition] writes nothing that leaves a change outside its step's [to].
 *
 * The first eight rows are the commands; the others are what those commands do on their way: a reservation undone, a
 * holder preempted, a publish that wrote no head or that did, and the expiry every read applies.
 */
internal enum class ChangeStep(val from: Set<ChangeState>, val to: Set<ChangeState>) {
    REBASE(setOf(CONFLICTED, DRAFT, CHECKED), setOf(CHECKED, DRAFT)),
    STAGE(setOf(CHECKED), setOf(SHADOWING, READY, AWAITING_APPROVAL)),
    SKIP_SHADOW(setOf(CHECKED), setOf(READY, AWAITING_APPROVAL)),
    SHADOWED(setOf(SHADOWING), setOf(SHADOWING, CONFLICTED, READY, AWAITING_APPROVAL)),
    APPROVE(setOf(AWAITING_APPROVAL), setOf(READY)),
    REJECT(setOf(AWAITING_APPROVAL), setOf(REJECTED)),
    ABANDON(open(), setOf(ABANDONED)),
    PUBLISH(setOf(READY), setOf(PUBLISHED, DRAFT, AWAITING_APPROVAL, CONFLICTED)),

    /** A stage or skip that did not get its slot: the change is `CHECKED` again. */
    UNRESERVE(setOf(SHADOWING, READY, AWAITING_APPROVAL), setOf(CHECKED)),

    /** A holder whose slot another change takes (RFC 0007 §5.5). */
    PREEMPT(ChangeState.entries.filter { it.staged }.toSet(), setOf(CONFLICTED)),

    /** A publish that wrote no head: the base moved, the slot was lost, or a policy is still referred to. */
    UNWRITTEN(setOf(READY, CONFLICTED), setOf(CONFLICTED, DRAFT)),

    /** A publish whose head is written: the change is published, whatever happened to it meanwhile. */
    WRITTEN(ChangeState.entries.toSet() - PUBLISHED, setOf(PUBLISHED)),

    /** A change past `cosec.control.changes.expire-after` and not finished. */
    EXPIRE(open(), setOf(EXPIRED)),
}

private fun open(): Set<ChangeState> = ChangeState.entries.filterNot { it.terminal }.toSet()
