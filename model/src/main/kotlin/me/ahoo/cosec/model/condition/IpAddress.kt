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
 * An IPv4 or IPv6 address, as 128 bits in [high] and [low]. An IPv4 address keeps its 32 bits in [low].
 *
 * IPv4-mapped IPv6 addresses (`::ffff:10.0.0.1`) are IPv4 addresses (RFC 0005 §3.2), so a CIDR block of either
 * family matches the same clients however the host reports them.
 */
class IpAddress private constructor(val v4: Boolean, val high: Long, val low: Long) {
    override fun equals(other: Any?): Boolean =
        other is IpAddress && other.v4 == v4 && other.high == high && other.low == low

    override fun hashCode(): Int = (31 * (31 * v4.hashCode() + high.hashCode())) + low.hashCode()

    override fun toString(): String = if (v4) formatV4(low) else formatV6(high, low)

    companion object {
        private const val V4_MAPPED_PREFIX = 0xffffL
        private const val V4_MASK = 0xffffffffL
        private const val GROUP_MASK = 0xffffL
        private const val GROUPS = 8
        private const val MAX_OCTET = 255

        fun v4(bits: Int): IpAddress = IpAddress(true, 0, bits.toLong() and V4_MASK)

        fun v6(high: Long, low: Long): IpAddress =
            if (high == 0L && (low ushr 32) == V4_MAPPED_PREFIX) {
                IpAddress(true, 0, low and V4_MASK)
            } else {
                IpAddress(false, high, low)
            }

        /**
         * Parses a literal address: dotted-quad IPv4 without leading zeros, or IPv6 with an optional `::` and an
         * optional dotted-quad tail. Zone ids, brackets and ports are not addresses. Returns null for anything else.
         */
        fun parse(text: String): IpAddress? =
            if (text.indexOf(':') < 0) parseV4(text)?.let { v4(it.toInt()) } else parseV6(text)

        /** The 32 bits of a dotted quad, or null. */
        private fun parseV4(text: String): Long? {
            val parts = text.split('.')
            if (parts.size != 4) {
                return null
            }
            var bits = 0L
            for (part in parts) {
                bits = (bits shl 8) or (octet(part) ?: return null).toLong()
            }
            return bits
        }

        private fun octet(text: String): Int? {
            val leadingZero = text.length > 1 && text[0] == '0'
            if (text.length !in 1..3 || leadingZero || text.any { it !in '0'..'9' }) {
                return null
            }
            return text.toInt().takeIf { it <= MAX_OCTET }
        }

        private fun parseV6(text: String): IpAddress? {
            val gap = text.indexOf("::")
            if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) {
                return null
            }
            val head = groups(if (gap < 0) text else text.substring(0, gap), dottedTail = gap < 0) ?: return null
            val tail = if (gap < 0) emptyList() else groups(text.substring(gap + 2), dottedTail = true) ?: return null
            val count = head.size + tail.size
            val complete = if (gap < 0) count == GROUPS else count < GROUPS
            if (!complete) {
                return null
            }
            val groups = head + List(GROUPS - count) { 0 } + tail
            var high = 0L
            var low = 0L
            for (g in 0 until GROUPS / 2) {
                high = (high shl 16) or groups[g].toLong()
                low = (low shl 16) or groups[g + GROUPS / 2].toLong()
            }
            return v6(high, low)
        }

        /** The 16-bit groups of `a:b:c`, or of `a:b:1.2.3.4` when a [dottedTail] is allowed. Empty for "". */
        private fun groups(text: String, dottedTail: Boolean): List<Int>? {
            if (text.isEmpty()) {
                return emptyList()
            }
            val parts = text.split(':')
            val groups = ArrayList<Int>(GROUPS)
            for ((i, part) in parts.withIndex()) {
                if (dottedTail && i == parts.size - 1 && '.' in part) {
                    val bits = parseV4(part) ?: return null
                    groups += (bits ushr 16).toInt()
                    groups += (bits and GROUP_MASK).toInt()
                } else {
                    groups += hexGroup(part) ?: return null
                }
            }
            return groups
        }

        private fun hexGroup(text: String): Int? {
            if (text.length !in 1..4 || text.any { Character.digit(it, 16) < 0 || it.code > 0x7f }) {
                return null
            }
            return text.toInt(16)
        }

        private fun formatV4(bits: Long): String =
            "${bits ushr 24 and 0xff}.${bits ushr 16 and 0xff}.${bits ushr 8 and 0xff}.${bits and 0xff}"

        /** RFC 5952: lower case, no leading zeros, the longest run of two or more zero groups as `::`. */
        private fun formatV6(high: Long, low: Long): String {
            val groups = IntArray(GROUPS) {
                val half = if (it < GROUPS / 2) high else low
                (half ushr (48 - 16 * (it % 4)) and GROUP_MASK).toInt()
            }
            var bestStart = -1
            var bestLength = 1
            var g = 0
            while (g < GROUPS) {
                var end = g
                while (end < GROUPS && groups[end] == 0) {
                    end++
                }
                if (end - g > bestLength) {
                    bestStart = g
                    bestLength = end - g
                }
                g = maxOf(end, g + 1)
            }
            if (bestStart < 0) {
                return groups.joinToString(":") { it.toString(16) }
            }
            val head = groups.copyOfRange(0, bestStart).joinToString(":") { it.toString(16) }
            val tail = groups.copyOfRange(bestStart + bestLength, GROUPS).joinToString(":") { it.toString(16) }
            return "$head::$tail"
        }
    }
}
