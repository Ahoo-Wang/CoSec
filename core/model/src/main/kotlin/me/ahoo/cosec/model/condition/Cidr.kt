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

/** A CIDR block: [address] with all host bits cleared, and the [prefix] length. */
@InternalCoSecApi
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

    @InternalCoSecApi
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
