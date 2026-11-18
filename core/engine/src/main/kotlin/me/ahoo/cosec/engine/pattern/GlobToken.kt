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

package me.ahoo.cosec.engine.pattern

/** A part of a [SegmentGlob]. */
internal sealed class GlobToken {
    /** Literal text; case-insensitive in ASCII only, as Spring's regular expression compares it. */
    class Literal(text: String, private val caseSensitive: Boolean) : GlobToken() {
        private val text: String = if (caseSensitive) text else asciiLowercase(text)
        val length: Int get() = text.length

        /** Whether [char] can start it: the first test of [matchesAt], cheap enough to make before calling it. */
        fun startsWith(char: Char): Boolean = char == text[0] || (!caseSensitive && asciiLowercase(char) == text[0])

        fun matchesAt(value: String, offset: Int): Boolean {
            val text = text
            if (offset + text.length > value.length) {
                return false
            }
            if (!caseSensitive) {
                for (index in text.indices) {
                    val char = value[offset + index]
                    if (char != text[index] && asciiLowercase(char) != text[index]) {
                        return false
                    }
                }
                return true
            }
            // The JDK compares longer regions a vector at a time; for a few characters its checks cost more.
            if (text.length >= VECTOR_LENGTH) {
                return value.regionMatches(offset, text, 0, text.length)
            }
            for (index in text.indices) {
                if (value[offset + index] != text[index]) {
                    return false
                }
            }
            return true
        }

        private companion object {
            const val VECTOR_LENGTH = 16
        }
    }

    /** `?`: one code point. */
    object AnyChar : GlobToken()

    /** `*` (no [variable]) or `{name}`: any number of code points, as many as the rest of the segment leaves. */
    class Run(val variable: Int) : GlobToken()

    companion object {
        fun asciiLowercase(char: Char): Char = if (char in 'A'..'Z') char + ('a' - 'A') else char

        fun asciiLowercase(text: String): String = String(CharArray(text.length) { asciiLowercase(text[it]) })
    }
}
