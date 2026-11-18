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

package me.ahoo.cosec.control.authz

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Makes the documents the control plane's checker evaluates current before a check (RFC 0007 §7.2): a conditional read
 * of each, so that a just-revoked administrator gets no lag window. The host wires it to the checker's own
 * `SnapshotSource` (one apart from the data plane's); a failure is an indeterminate decision. It answers a version of
 * each document it confirmed, a value that equals another only while the document is the same (its revision: the
 * control plane moves a document only to a higher one), so that [ControlAuthorizer.batch] can tell whether one moved.
 */
@InternalCoSecApi
fun interface DocumentConfirmation {
    suspend fun confirm(keys: Set<SnapshotKey>): Map<SnapshotKey, Any>

    @InternalCoSecApi
    companion object {
        /** For a checker whose documents are always current, such as an in-memory one: no versions, none to compare. */
        val CURRENT: DocumentConfirmation = DocumentConfirmation { emptyMap() }
    }
}
