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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.random.Random

class IpTest {
    @Test
    fun `addresses parse and print in canonical form`() {
        mapOf(
            "10.0.0.1" to "10.0.0.1",
            "0.0.0.0" to "0.0.0.0",
            "255.255.255.255" to "255.255.255.255",
            "::" to "::",
            "::1" to "::1",
            "1::" to "1::",
            "2001:DB8::1" to "2001:db8::1",
            "2001:0db8:0000:0000:0000:0000:0000:0001" to "2001:db8::1",
            "2001:db8:0:1:1:1:1:1" to "2001:db8:0:1:1:1:1:1",
            "2001:0:0:1::1" to "2001:0:0:1::1",
            "1:0:0:2:0:0:0:3" to "1:0:0:2::3",
            "::ffff:10.0.0.1" to "10.0.0.1",
            "::FFFF:a00:1" to "10.0.0.1",
            "64:ff9b::10.0.0.1" to "64:ff9b::a00:1",
            "::10.0.0.1" to "::a00:1",
        ).forEach { (text, canonical) ->
            IpAddress.parse(
                text
            ).toString().assert().describedAs(text).isEqualTo(canonical)
        }
        IpAddress.parse("::ffff:10.0.0.1").assert().isEqualTo(IpAddress.parse("10.0.0.1"))
        IpAddress.parse("::ffff:10.0.0.1")?.v4.assert().isEqualTo(true)
        IpAddress.parse("10.0.0.1").hashCode().assert().isEqualTo(IpAddress.v4(0x0a000001).hashCode())
        IpAddress.parse("10.0.0.1").assert().isNotEqualTo(IpAddress.parse("::a00:1"))
        IpAddress.parse("10.0.0.1").assert().isNotEqualTo(Any())
    }

    @Test
    fun `anything else is not an address`() {
        listOf(
            "", "1", "1.2.3", "1.2.3.4.5", "256.0.0.1", "01.2.3.4", "1.2.3.04", "1..2.3", "1.2.3.", ".1.2.3", "1.2.3.4 ",
            "a.b.c.d", "1234.1.1.1", ":", ":::", "1:::2", "1::2::3", ":1::", "1:", "::1:", "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7", "12345::", "g::", "::1.2.3", "::1.2.3.4.5", "1:2:3:4:5:6:7:1.2.3.4", "fe80::1%eth0", "[::1]",
            "1::2:3:4:5:6:7:8", "::ffff:256.0.0.1", "1.2.3.4:80",
        ).forEach { IpAddress.parse(it).assert().describedAs(it).isNull() }
    }

    @Test
    fun `parsing agrees with the JDK on random addresses`() {
        val random = Random(5)
        repeat(5_000) {
            val bytes = random.nextBytes(if (random.nextBoolean()) 4 else 16)
            if (bytes.size == 16 && random.nextInt(4) == 0) bytes.fill(0, 0, random.nextInt(17))
            val jdk = InetAddress.getByAddress(bytes)
            val texts = listOf(jdk.hostAddress, jdk.hostAddress.uppercase())
            for (text in texts) {
                val parsed = IpAddress.parse(text)
                // The JDK maps ::ffff:a.b.c.d to IPv4 as well.
                val expected = InetAddress.getByName(text)
                parsed.assert().describedAs(text).isNotNull()
                parsed!!.v4.assert().describedAs(text).isEqualTo(expected is Inet4Address)
                if (expected is Inet6Address) {
                    IpAddress.parse(parsed.toString()).assert().isEqualTo(parsed)
                    InetAddress.getByName(parsed.toString()).assert().describedAs(text).isEqualTo(expected)
                } else {
                    parsed.toString().assert().isEqualTo(expected.hostAddress)
                }
            }
        }
    }

    @Test
    fun `blocks clear their host bits`() {
        mapOf(
            "10.1.2.3/8" to "10.0.0.0/8",
            "10.0.0.1" to "10.0.0.1/32",
            "0.0.0.0/0" to "0.0.0.0/0",
            "1.2.3.4/0" to "0.0.0.0/0",
            "2001:db8::1/32" to "2001:db8::/32",
            "2001:db8::1" to "2001:db8::1/128",
            "::/0" to "::/0",
            "ffff::1/1" to "8000::/1",
            "2001:db8:1:2:3:4:5:6/100" to "2001:db8:1:2:3:4::/100",
            "::ffff:10.1.0.0/104" to "10.0.0.0/8",
            "::ffff:0:0/96" to "0.0.0.0/0",
        ).forEach { (text, canonical) ->
            CidrBlock.parse(
                text
            ).toString().assert().describedAs(text).isEqualTo(canonical)
        }
        listOf("10.0.0.0/33", "10.0.0.0/", "10.0.0.0/08", "10.0.0.0/-1", "10.0.0.0/a", "::/129", "::/1000", "::ffff:0:0/95", "x/8", "10.0.0.0/8/8")
            .forEach { CidrBlock.parse(it).assert().describedAs(it).isNull() }
        CidrBlock.parse("10.0.0.0/8").assert().isEqualTo(CidrBlock.parse("10.9.9.9/8"))
        CidrBlock.parse("10.0.0.0/8").hashCode().assert().isEqualTo(CidrBlock.parse("10.9.9.9/8").hashCode())
        CidrBlock.parse("10.0.0.0/8").assert().isNotEqualTo(CidrBlock.parse("10.0.0.0/9"))
        CidrBlock.parse("10.0.0.0/8").assert().isNotEqualTo(Any())
        runCatching { CidrBlock.of(IpAddress.v4(0), 33) }.isFailure.assert().isTrue()
    }

    @Test
    fun `ranges contain exactly the addresses of their blocks`() {
        val random = Random(11)
        repeat(300) {
            val v4 = random.nextBoolean()
            val blocks = List(random.nextInt(1, 12)) { randomBlock(random, v4) }
            val ranges = IpRanges(blocks)
            ranges.assert().isEqualTo(IpRanges(blocks.reversed()))
            ranges.hashCode().assert().isEqualTo(IpRanges(blocks.reversed()).hashCode())
            ranges.toString().assert().isEqualTo(ranges.blocks.toString())
            repeat(200) {
                val probe = if (random.nextBoolean()) {
                    near(
                        random,
                        blocks.random(random)
                    )
                } else {
                    randomAddress(random, v4 xor (random.nextInt(8) == 0))
                }
                val expected = blocks.any { contains(it, probe) }
                (probe in ranges).assert().describedAs("$probe in $blocks").isEqualTo(expected)
            }
        }
    }

    private fun randomAddress(random: Random, v4: Boolean): IpAddress =
        if (v4) IpAddress.v4(random.nextInt()) else IpAddress.v6(random.nextLong(), random.nextLong())

    private fun randomBlock(random: Random, v4: Boolean): CidrBlock {
        val address = randomAddress(random, v4)
        val bits = if (address.v4) 32 else 128
        return CidrBlock.of(address, random.nextInt(0, bits + 1))
    }

    /** An address at, just inside or just outside an edge of [block]. */
    private fun near(random: Random, block: CidrBlock): IpAddress {
        val delta = random.nextLong(-2, 3)
        val high = if (random.nextBoolean()) block.startHigh else block.endHigh
        val low = (if (random.nextBoolean()) block.startLow else block.endLow) + delta
        return if (block.address.v4) IpAddress.v4(low.toInt()) else IpAddress.v6(high, low)
    }

    private fun contains(block: CidrBlock, address: IpAddress): Boolean {
        if (block.address.v4 != address.v4) return false
        val a = (address.high.toULong().toString(2).padStart(64, '0') + address.low.toULong().toString(2).padStart(64, '0'))
        val b = (block.address.high.toULong().toString(2).padStart(64, '0') + block.address.low.toULong().toString(2).padStart(64, '0'))
        val offset = if (address.v4) 96 else 0
        return a.substring(offset, offset + block.prefix) == b.substring(offset, offset + block.prefix)
    }
}
