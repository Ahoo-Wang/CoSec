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
 * ASCII case folding (RFC 0005 §3.2): `ignoreCase` folds `A`–`Z` only, so the result never depends on a locale or on
 * Unicode tables. Literals are folded once when a condition is checked; request values are compared without copying.
 */
internal object Ascii {
    fun lowercase(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

    fun uppercase(c: Char): Char = if (c in 'a'..'z') c - ('a' - 'A') else c

    fun lowercase(value: String): String {
        if (value.none { it in 'A'..'Z' }) {
            return value
        }
        val chars = CharArray(value.length) { lowercase(value[it]) }
        return String(chars)
    }

    fun uppercase(value: String): String = String(CharArray(value.length) { uppercase(value[it]) })

    /** Whether [value], folded, equals [folded], which is already in lower case. */
    fun equalsFolded(value: String, folded: String): Boolean =
        value.length == folded.length && regionMatchesFolded(value, 0, folded)

    /** Whether [value], folded, starts with [folded], which is already in lower case. */
    fun startsWithFolded(value: String, folded: String): Boolean =
        value.length >= folded.length && regionMatchesFolded(value, 0, folded)

    /** Whether the [folded] values of both strings are equal. */
    fun equalsIgnoreCase(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }
        for (i in a.indices) {
            if (lowercase(a[i]) != lowercase(b[i])) {
                return false
            }
        }
        return true
    }

    /** Whether [value] from [offset] on, folded, starts with [folded]. The caller checks the length. */
    fun regionMatchesFolded(value: String, offset: Int, folded: String): Boolean {
        for (i in folded.indices) {
            if (lowercase(value[offset + i]) != folded[i]) {
                return false
            }
        }
        return true
    }
}
