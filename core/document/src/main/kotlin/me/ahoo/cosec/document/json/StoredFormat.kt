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

package me.ahoo.cosec.document.json

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The reader's rule for CoSec's stored records (R2C-10, R2C-28, `CTL-53`, `IDN-31`): each carries `"format": <n>`.
 * - A record without one was written before formats, and is format 1.
 * - Of a format the reader knows, it reads the members it knows and ignores the others; a member it does not find
 *   takes its documented default.
 * - A higher format is refused, as a [SnapshotFormatException]: never read as absent.
 *
 * So a member added within a format is optional, and an older instance may drop it when it rewrites the record;
 * anything an older instance must not drop or ignore needs a new format number, which older readers refuse. The
 * policy documents (`schema`), the API keys and the back-reference members are decision inputs and stay strict.
 */
@InternalCoSecApi
object StoredFormat {
    const val FIELD: String = "format"

    /** Writes [format] into [node] as its format member. */
    fun write(node: ObjectNode, format: Int = 1): ObjectNode = node.put(FIELD, format)

    /** The format of [node], a stored [what]: 1 without one; refused above [known]. */
    fun of(node: JsonNode, what: String, known: Int = 1): Int {
        val format = if (node.isObject) node.get(FIELD) else null
        val read = format?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
        val refused = when {
            !node.isObject -> "The $what is not an object."
            format == null -> return 1
            read == null || read < 1 -> "The $what has a format that is not a positive integer."
            read > known -> "The $what is of format $read, which a later CoSec wrote; this version reads format $known and before."
            else -> return read
        }
        throw SnapshotFormatException(refused)
    }
}
