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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * A request path split the way Spring's `PathContainer.parsePath` splits it (RFC 0005 §5.2), parsed once per request
 * and matched by every [HttpPathPattern].
 *
 * The path is a sequence of elements: a separator for every `/`, and a segment for every non-empty text between two of
 * them, so `/a//b/` is separator, `a`, separator, separator, `b`, separator. With [decodeAndParseSegments], a segment
 * is matched by its value without `;` parameters, percent-decoded as UTF-8; otherwise by its text as written, except
 * that `%2F` reads as `/`, as in Spring.
 */
@InternalCoSecApi
class HttpPath private constructor(
    val value: String,
    val decodeAndParseSegments: Boolean,
    /** The value each segment is matched by, or `null` for a separator. */
    private val elements: Array<String?>,
    /** The length of its longest segment as written, before anything is decoded or removed. */
    private val longestSegment: Int,
) {
    /**
     * `PIP-5`: whether a pattern that decodes ([decodes]) or not may read it. One that does not reads segments as
     * written, which the pipeline bounds only decoded, so it reads only a path whose segments are within
     * [MAX_SEGMENT_LENGTH] as written: a longer one would cost it up to ten times as much (`SegmentBoundBenchmark`).
     * It is an ALLOW (`DOC-14`), so matching less fails closed. It is where CoSec matches less than Spring does.
     */
    internal fun readableBy(decodes: Boolean): Boolean = decodes || longestSegment <= MAX_SEGMENT_LENGTH

    internal val size: Int get() = elements.size

    internal fun isSeparator(index: Int): Boolean = elements[index] == null

    /** The segment at [index], or `null` for a separator. */
    internal fun segment(index: Int): String? = elements[index]

    /** The elements from [from] to [to] as one string, separators as `/`: what `{*name}` captures. */
    internal fun join(from: Int, to: Int): String {
        val joined = StringBuilder()
        for (index in from until to) {
            joined.append(elements[index] ?: SEPARATOR_TEXT)
        }
        return joined.toString()
    }

    override fun toString(): String = value

    @InternalCoSecApi
    companion object {
        const val SEPARATOR: Char = '/'

        /**
         * The longest segment a request's path holds (`PIP-5`), in UTF-16 units: once decoded for every pattern that
         * decodes (`PathNormalizer` refuses a longer one), as written for one that matches the path as received
         * ([HttpPathPattern.decodeAndParseSegments] false), which matches no path with a longer segment. Matching costs
         * a glob's units times the segment's length as the pattern reads it, so either way no segment costs more than
         * one of 256 characters.
         */
        const val MAX_SEGMENT_LENGTH: Int = 256
        private const val SEPARATOR_TEXT = "/"

        /** Spring decodes an encoded separator even when it decodes nothing else; only in upper case. */
        private const val ENCODED_SEPARATOR = "%2F"

        /**
         * Parses [path]. With [decodeAndParseSegments], a malformed percent-escape (`%zz`, or a `%` without two hex
         * digits) throws [IllegalArgumentException], as it does in Spring: such a path has no defined meaning, and the
         * pipeline rejects it before authorization (`PathNormalizer`).
         */
        fun parse(path: String, decodeAndParseSegments: Boolean = true): HttpPath {
            // Sized exactly up front, and segments copied as they are unless the path has anything to decode: parsing
            // runs once per request, and growing a list or scanning character by character dominated it.
            val elements = arrayOfNulls<String>(countElements(path))
            val plain = path.indexOf(PercentDecoding.ESCAPE) < 0 &&
                (!decodeAndParseSegments || path.indexOf(PercentDecoding.PARAMETER) < 0)
            var count = 0
            var begin = 0
            var longest = 0
            while (begin < path.length) {
                val end = path.indexOf(SEPARATOR, begin).let { if (it < 0) path.length else it }
                if (end > begin) {
                    longest = maxOf(longest, end - begin)
                    val segment = path.substring(begin, end)
                    elements[count++] = if (plain) segment else valueToMatch(segment, decodeAndParseSegments)
                }
                if (end == path.length) {
                    break
                }
                // A separator stays null.
                count++
                begin = end + 1
            }
            return HttpPath(path, decodeAndParseSegments, elements, longest)
        }

        /** One element per `/`, and one per non-empty text between them. */
        private fun countElements(path: String): Int {
            var count = 0
            var begin = 0
            while (begin < path.length) {
                val end = path.indexOf(SEPARATOR, begin).let { if (it < 0) path.length else it }
                if (end > begin) {
                    count++
                }
                if (end == path.length) {
                    break
                }
                count++
                begin = end + 1
            }
            return count
        }

        private fun valueToMatch(segment: String, decodeAndParseSegments: Boolean): String =
            if (decodeAndParseSegments) {
                PercentDecoding.valueToMatch(segment)
            } else {
                segment.replace(ENCODED_SEPARATOR, SEPARATOR_TEXT)
            }
    }
}
