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

package me.ahoo.cosec.model.condition

/**
 * HTTP names as CoSec compares them: a header name is a token (RFC 9110 §5.6.2: ASCII letters, digits and
 * ``!#$%&'*+-.^_`|~``), matched case-insensitively (§5.1) with the ASCII fold. The document side ([Attribute]) and the
 * request side (the runtime's headers and the hosts of `Origin` and `Referer`) fold with this one function, so no
 * Unicode case mapping (`K`, U+212A, folds to `k`) can make two different names meet.
 */
object HttpNames {
    /** Whether [name] is a non-empty token. */
    fun isToken(name: String): Boolean = name.isNotEmpty() && name.all(::isTokenChar)

    /** [value] with `A`–`Z` folded to `a`–`z`, and nothing else changed. */
    fun fold(value: String): String = Ascii.lowercase(value)

    private fun isTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in TOKEN_SYMBOLS

    private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"
}
