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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * The states of a change request (RFC 0007 §6.1). Every transition is a conditional write of the request, so of two
 * concurrent ones (approve and abandon, say) only one happens.
 *
 * ```
 * DRAFT -> CHECKED -> SHADOWING -> SHADOWED -> (AWAITING_APPROVAL ->) READY -> PUBLISHED
 *            \--- skip-shadow ---/
 * SHADOWING, AWAITING_APPROVAL, READY -> CONFLICTED when the base changes; CONFLICTED -> DRAFT by rebase
 * ```
 */
@ExperimentalCoSecApi
enum class ChangeState {
    /** Proposed, with the findings of its last check; not checked clean. */
    DRAFT,

    /** Checked clean against its base: validation, compile, authorization and grants. */
    CHECKED,

    /** Holding the document's staging slot while its shadow window runs (RFC 0007 §6.4). */
    SHADOWING,

    SHADOWED,
    AWAITING_APPROVAL,
    READY,

    /** The base changed under it; nothing of it was published. [Changes.rebase] replays it on the new head. */
    CONFLICTED,

    PUBLISHED,
    REJECTED,
    ABANDONED,
    EXPIRED,
    ;

    val terminal: Boolean get() = this == PUBLISHED || this == REJECTED || this == ABANDONED || this == EXPIRED

    /** Whether a change in this state holds its document's staging slot (RFC 0007 §5.5). */
    val staged: Boolean get() = this == SHADOWING || this == SHADOWED || this == AWAITING_APPROVAL || this == READY
}
