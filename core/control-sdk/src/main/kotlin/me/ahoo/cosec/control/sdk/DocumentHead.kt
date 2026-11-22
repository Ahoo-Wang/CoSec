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
 * The head of a document, as `GET /documents/{key}` answers it: its [revision], when it was published, and its
 * [content]. A change proposed on it names [revision] as its base.
 */
@ExperimentalCoSecApi
class DocumentHead(val key: SnapshotKey, val revision: Long, val publishedAt: Instant, val content: DocumentContent) {
    override fun equals(other: Any?): Boolean = other is DocumentHead && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "DocumentHead",
        "key" to key,
        "revision" to revision,
        "publishedAt" to publishedAt,
        "content" to content,
    )

    private fun parts(): List<Any?> = listOf(key, revision, publishedAt, content)
}
