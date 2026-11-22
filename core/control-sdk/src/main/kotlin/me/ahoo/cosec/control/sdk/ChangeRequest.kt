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
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * A proposal (`POST /changes`): [operations] on [document], proposed on revision [base] of it (the head's revision a
 * caller read; 0 for a document not published yet), with a [note] the change keeps. A base that has moved since is a
 * 409 that names [ConflictKind.BASE_MOVED].
 */
@ExperimentalCoSecApi
class ChangeRequest(
    val document: SnapshotKey,
    val base: Long,
    operations: List<Operation>,
    val note: String = "",
) {
    val operations: List<Operation> = operations.toList()

    override fun equals(other: Any?): Boolean = other is ChangeRequest && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "ChangeRequest",
        "document" to document,
        "base" to base,
        "operations" to operations,
        "note" to note,
    )

    private fun parts(): List<Any?> = listOf(document, base, operations, note)
}
