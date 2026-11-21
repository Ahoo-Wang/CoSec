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

/**
 * A resource a statement, a catalog permission or a rate limit rule names (the document format's `resource`):
 * `type/segment/...` or `http:path:<pattern>`, with the two options an `http:path:` pattern takes. The wire writes one
 * with both options at their defaults as its pattern alone.
 */
@ExperimentalCoSecApi
class Resource(
    val pattern: String,
    val caseSensitive: Boolean = true,
    val decodeAndParseSegments: Boolean = true,
) {
    override fun equals(other: Any?): Boolean = other is Resource && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Resource",
        "pattern" to pattern,
        "caseSensitive" to caseSensitive,
        "decodeAndParseSegments" to decodeAndParseSegments,
    )

    private fun parts(): List<Any?> = listOf(pattern, caseSensitive, decodeAndParseSegments)
}
