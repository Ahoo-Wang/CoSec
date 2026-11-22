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
import java.time.Instant

/**
 * A change as `GET /changes` lists it (`contract/control/change-summary.json`): its [id], document and [state] always;
 * the rest only for a change the caller may read, null otherwise.
 */
@ExperimentalCoSecApi
class ChangeSummary(
    val id: String,
    val key: SnapshotKey,
    val state: ChangeState,
    val kind: ChangeKind? = null,
    val note: String? = null,
    val author: Actor? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    override fun equals(other: Any?): Boolean = other is ChangeSummary && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "ChangeSummary",
        "id" to id,
        "key" to key,
        "state" to state,
        "kind" to kind,
        "note" to note,
        "author" to author,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )

    private fun parts(): List<Any?> = listOf(id, key, state, kind, note, author, createdAt, updatedAt)
}
