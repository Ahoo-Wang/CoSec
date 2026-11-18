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

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * A set of CIDR blocks. The families are separate: IPv4 clients, including IPv4-mapped IPv6 ones, match IPv4 blocks
 * only.
 *
 * Compiled into sorted, disjoint ranges per family, so a lookup is a binary search
 * (RFC 0005 §3.3): O(log n), no allocation.
 */
@InternalCoSecApi
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
