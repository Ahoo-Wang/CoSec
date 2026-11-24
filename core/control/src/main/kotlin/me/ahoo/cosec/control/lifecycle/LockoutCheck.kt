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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * UC-6: whether [Operator] could still reach the control plane once a candidate replaces its document. A change to a
 * document that gates the control plane for more than its own tenant (the platform's, the platform tenant's, the
 * control app's) can leave nobody able to change it back, and the way back is then `cosec-migrate restore`.
 *
 * Checked with the operator's own request: the request in progress, and `cosec:publish` on the document, decided by
 * the engine with the candidate in place. At proposal a lock-out is a `WARNING` for the proposer; at publish it is a
 * `422` for the publisher unless they acknowledge it (`"lockout": true`), as a widening shadow window needs its numbers.
 * The check's limits are its implementation's to state (`ControlLockout`).
 */
@InternalCoSecApi
fun interface LockoutCheck {
    /**
     * Why [operator] could not reach the control plane after [candidate] replaces [key]: a [Lockout]; null when they
     * could, or when [key] is not a document this check covers.
     */
    suspend fun check(operator: Operator, key: SnapshotKey, candidate: PolicyDocument): Lockout?

    /**
     * What keeps the operator out: [reason] says which request is not permitted, and what decides it. When it names a
     * statement, [namedIn] are the documents whose policy that is (all of them, for an attachment), and [unnamed] says
     * the same without the statement: an operator who may not read each of [namedIn] is told [unnamed] (`CTL-51`, as
     * `simulate` shows a statement; DR1-F-13).
     */
    @InternalCoSecApi
    class Lockout(val reason: String, val namedIn: List<SnapshotKey> = emptyList(), val unnamed: String = reason)

    @InternalCoSecApi
    companion object {
        /** The check name of the finding (RFC 0007 §6.2). */
        const val CHECK: String = "V4"

        /** No check: for a control plane whose documents gate no control API (tests, embedded tools). A choice to write. */
        val UNCHECKED: LockoutCheck = LockoutCheck { _, _, _ -> null }
    }
}
