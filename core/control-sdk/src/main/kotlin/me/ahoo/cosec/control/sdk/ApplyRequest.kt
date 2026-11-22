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
 * `POST /documents/{key}:apply` (RFC 0007 §6.7): proposes the operations that make [document]'s head into [content].
 * [ApplyMode.MERGE] adds and changes, and proposes on the head; [ApplyMode.REPLACE] also removes what [content] leaves
 * out, so it is proposed on [base], the revision [content] was read at: one published since is a 409 instead of
 * removing what it added.
 */
@ExperimentalCoSecApi
class ApplyRequest(
    val document: SnapshotKey,
    val base: Long,
    val content: DocumentContent,
    val mode: ApplyMode,
    val note: String = "",
) {
    override fun equals(other: Any?): Boolean = other is ApplyRequest && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "ApplyRequest",
        "document" to document,
        "base" to base,
        "content" to content,
        "mode" to mode,
        "note" to note,
    )

    private fun parts(): List<Any?> = listOf(document, base, content, mode, note)
}
