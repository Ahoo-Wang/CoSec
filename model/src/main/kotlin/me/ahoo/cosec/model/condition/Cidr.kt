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

/** A CIDR block: [address] with all host bits cleared, and the [prefix] length. */
class CidrBlock private constructor(val address: IpAddress, val prefix: Int) : Comparable<CidrBlock> {
    internal val startHigh: Long
    internal val startLow: Long
    internal val endHigh: Long
    internal val endLow: Long

    init {
        if (address.v4) {
            val host = if (prefix == 0) 0xffffffffL else (1L shl (32 - prefix)) - 1
            startHigh = 0
            endHigh = 0
            startLow = address.low
            endLow = address.low or host
        } else {
            val hostHigh = mask(prefix)
            val hostLow = mask(prefix - 64)
            startHigh = address.high
            startLow = address.low
            endHigh = address.high or hostHigh
            endLow = address.low or hostLow
        }
    }

    override fun compareTo(other: CidrBlock): Int = when {
        address.v4 != other.address.v4 -> if (address.v4) -1 else 1
        startHigh != other.startHigh -> java.lang.Long.compareUnsigned(startHigh, other.startHigh)
        startLow != other.startLow -> java.lang.Long.compareUnsigned(startLow, other.startLow)
        else -> prefix.compareTo(other.prefix)
    }

    override fun equals(other: Any?): Boolean = other is CidrBlock && other.address == address && other.prefix == prefix

    override fun hashCode(): Int = 31 * address.hashCode() + prefix

    override fun toString(): String = "$address/$prefix"

    companion object {
        /** The host bits of a 64-bit half whose first [prefix] bits belong to the network. */
        private fun mask(prefix: Int): Long = when {
            prefix <= 0 -> -1L
            prefix >= 64 -> 0L
            else -> (1L shl (64 - prefix)) - 1
        }

        /**
         * Parses `address/prefix` or a bare address (a block of one). Host bits are cleared, as every CIDR
         * implementation does: `10.1.2.3/8` is `10.0.0.0/8`. An IPv4-mapped IPv6 block is the IPv4 block it maps
         * to, and must not reach outside `::ffff:0:0/96`. Returns null if the text is not a block.
         */
        fun parse(text: String): CidrBlock? {
            val slash = text.indexOf('/')
            val addressText = if (slash < 0) text else text.substring(0, slash)
            val address = IpAddress.parse(addressText) ?: return null
            val mappedV6 = address.v4 && addressText.indexOf(':') >= 0
            val bits = if (address.v4 && !mappedV6) 32 else 128
            var prefix = if (slash < 0) bits else parsePrefix(text, slash + 1, bits) ?: return null
            if (mappedV6) {
                if (prefix < 96) {
                    return null
                }
                prefix -= 96
            }
            return of(address, prefix)
        }

        fun of(address: IpAddress, prefix: Int): CidrBlock {
            val bits = if (address.v4) 32 else 128
            require(prefix in 0..bits) { "Prefix $prefix is out of 0..$bits." }
            val network = if (address.v4) {
                val host = if (prefix == 0) 0xffffffffL else (1L shl (32 - prefix)) - 1
                IpAddress.v4((address.low and host.inv()).toInt())
            } else {
                IpAddress.v6(address.high and mask(prefix).inv(), address.low and mask(prefix - 64).inv())
            }
            return CidrBlock(network, prefix)
        }

        private fun parsePrefix(text: String, start: Int, bits: Int): Int? {
            val length = text.length - start
            if (length !in 1..3 || (length > 1 && text[start] == '0')) {
                return null
            }
            var value = 0
            for (i in start until text.length) {
                val c = text[i]
                if (c !in '0'..'9') {
                    return null
                }
                value = value * 10 + (c - '0')
            }
            return if (value <= bits) value else null
        }
    }
}

/**
 * A set of CIDR blocks. The families are separate: IPv4 clients, including IPv4-mapped IPv6 ones, match IPv4 blocks
 * only.
 *
 * Compiled into sorted, disjoint ranges per family, so a lookup is a binary search
 * (RFC 0005 §3.3): O(log n), no allocation.
 */
class IpRanges(blocks: Collection<CidrBlock>) {
    /** The blocks, sorted and without duplicates. */
    val blocks: List<CidrBlock> = blocks.toSortedSet().toList()

    private val v4Start: LongArray
    private val v4End: LongArray
    private val v6StartHigh: LongArray
    private val v6StartLow: LongArray
    private val v6EndHigh: LongArray
    private val v6EndLow: LongArray

    init {
        val v4 = merge(this.blocks.filter { it.address.v4 })
        v4Start = LongArray(v4.size) { v4[it][1] }
        v4End = LongArray(v4.size) { v4[it][3] }
        val v6 = merge(this.blocks.filterNot { it.address.v4 })
        v6StartHigh = LongArray(v6.size) { v6[it][0] }
        v6StartLow = LongArray(v6.size) { v6[it][1] }
        v6EndHigh = LongArray(v6.size) { v6[it][2] }
        v6EndLow = LongArray(v6.size) { v6[it][3] }
    }

    operator fun contains(address: IpAddress): Boolean =
        if (address.v4) {
            containsV4(address.low)
        } else {
            containsV6(address.high, address.low)
        }

    private fun containsV4(value: Long): Boolean {
        var low = 0
        var high = v4Start.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                value < v4Start[mid] -> high = mid - 1
                value > v4End[mid] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun containsV6(valueHigh: Long, valueLow: Long): Boolean {
        var low = 0
        var high = v6StartHigh.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                compare(valueHigh, valueLow, v6StartHigh[mid], v6StartLow[mid]) < 0 -> high = mid - 1
                compare(valueHigh, valueLow, v6EndHigh[mid], v6EndLow[mid]) > 0 -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    override fun equals(other: Any?): Boolean = other is IpRanges && other.blocks == blocks

    override fun hashCode(): Int = blocks.hashCode()

    override fun toString(): String = blocks.toString()

    private companion object {
        fun compare(aHigh: Long, aLow: Long, bHigh: Long, bLow: Long): Int =
            if (aHigh != bHigh) {
                java.lang.Long.compareUnsigned(
                    aHigh,
                    bHigh
                )
            } else {
                java.lang.Long.compareUnsigned(aLow, bLow)
            }

        /** Sorted blocks of one family to disjoint `[startHigh, startLow, endHigh, endLow]` ranges. */
        fun merge(sorted: List<CidrBlock>): List<LongArray> {
            val ranges = ArrayList<LongArray>()
            for (block in sorted) {
                val last = ranges.lastOrNull()
                if (last != null && compare(block.startHigh, block.startLow, last[2], last[3]) <= 0) {
                    if (compare(block.endHigh, block.endLow, last[2], last[3]) > 0) {
                        last[2] = block.endHigh
                        last[3] = block.endLow
                    }
                } else {
                    ranges.add(longArrayOf(block.startHigh, block.startLow, block.endHigh, block.endLow))
                }
            }
            return ranges
        }
    }
}
