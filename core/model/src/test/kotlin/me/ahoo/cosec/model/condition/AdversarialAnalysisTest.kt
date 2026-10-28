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
import kotlin.random.Random

/**
 * A1 and A2 against adversarial pools: case folding outside ASCII (Kelvin sign, dotted and dotless i, sharp s,
 * combining marks), empty strings, host names with trailing or leading dots, IPv4-mapped IPv6 addresses and blocks,
 * `/0` blocks of both families, daily windows that cross midnight, zones with daylight saving, and sub-millisecond
 * period bounds. [AnalysisPropertyTest] samples friendly pools; this one samples the edges.
 */
class AdversarialAnalysisTest {
    private val strings = listOf("subject.id", "http.origin.host", "http.header.x-key").map(::attr)
    private val bool = attr("subject.authenticated")
    private val roles = attr("scope.roles")
    private val ip = attr("environment.ip")
    private val labels = attr("http.origin.host.labels")
    private val values = listOf(
        "", "a", "A", "ab", "AB", "aB", "abc", "k", "K", "K", "Key", "key", "KEY",
        "i", "I", "İ", "ı", "ıd", "id", "ss", "SS", "ß", "é", "é", "É",
        "example.com", "EXAMPLE.com", "api.example.com", "example.com.", ".example.com", "xexample.com",
        "com", "com.example",
    )
    private val labelLiterals = listOf("com", "com.example", "com.example.api", "com.examplE", "org")
    private val blocks = listOf(
        "0.0.0.0/0", "::/0", "::ffff:0:0/96", "::ffff:10.0.0.0/104", "10.0.0.0/8", "10.1.2.3",
        "255.255.255.255/32", "2001:db8::/32", "2001:db8::1", "::/128", "::1",
        "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128", "8000::/1", "128.0.0.0/1",
    )
    private val addresses = listOf(
        "0.0.0.0", "10.1.2.3", "::ffff:10.1.2.3", "255.255.255.255", "128.0.0.0", "127.255.255.255",
        "::", "::1", "2001:db8::1", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "8000::", "7fff:ffff:ffff:ffff::",
    ).map { IpAddress.parse(it)!! }
    private val instants = listOf(
        "2026-03-08T01:59:59.9999999-05:00",
        "2026-03-08T07:00:00Z",
        "2026-03-08T07:00:00.000000001Z",
        "2026-03-29T01:00:00Z",
        "2026-11-01T01:30:00",
    )
    private val zones = listOf("UTC", "+08:00", "-14:00", "America/New_York", "Europe/London")
    private val clock = listOf("00:00", "01:30", "23:00", "23:59:59.999999999", "02:00", "12:00")

    /** Epoch millis near the instants above and near midnight and DST transitions in the zones above. */
    private val times: List<Long> by lazy {
        val base = listOf(
            1_772_953_199_999L, 1_772_953_200_000L, 1_772_953_200_001L, 1_774_746_000_000L,
            1_774_746_000_000L - 3_600_000L, 1_793_511_000_000L, 1_793_514_600_000L, 1_793_518_200_000L,
            1_767_225_600_000L, 1_767_139_200_000L + 86_399_999L,
        )
        base.flatMap { listOf(it - 1, it, it + 1, it + 3_600_000L, it - 3_600_000L, it + 18_000_000L) }
    }

    @Test
    fun `A1 holds on adversarial values`() {
        val random = Random(0xADA1)
        repeat(maxOf(10_000, Integer.getInteger("cosec.property.iterations", 0))) {
            val condition = checkedOrNull(condition(random, 1)) ?: return@repeat
            val keys = strings.associateWith { IndexKeys.of(condition, it) }
            repeat(100) {
                val input = input(random)
                if (condition.evaluate(input)) {
                    for ((attribute, key) in keys) {
                        key.admits(input.strings[attribute]).assert()
                            .describedAs("$condition matches ${input.strings} outside $key").isTrue()
                    }
                }
            }
        }
    }

    @Test
    fun `A2 holds on adversarial values`() {
        val random = Random(0xADA2)
        var yes = 0
        repeat(maxOf(40_000, Integer.getInteger("cosec.property.iterations", 0))) {
            val (left, right) = pair(random)
            val c1 = checkedOrNull(left) ?: return@repeat
            val c2 = checkedOrNull(right) ?: return@repeat
            if (ConditionContainment.implies(c1, c2) != Containment.YES) {
                return@repeat
            }
            yes++
            repeat(500) {
                val input = input(random)
                if (c1.evaluate(input)) {
                    c2.evaluate(input).assert().describedAs("implies($c1, $c2) = YES, but not for $input").isTrue()
                }
            }
        }
        yes.assert().isGreaterThan(3_000)
    }

    private fun checkedOrNull(condition: Condition): CheckedCondition? =
        (ConditionChecker(zones = JavaZones).check(condition) as? CheckResult.Valid)?.condition

    private fun pair(random: Random): Pair<Condition, Condition> = when (random.nextInt(5)) {
        0 -> condition(random, 2).let { Condition.And(listOf(condition(random, 2), it)) to it }
        1 -> condition(random, 2).let { it to Condition.Or(listOf(condition(random, 3), it)) }
        2 -> leaf(random) to leaf(random)
        3 -> leaf(random).let { a ->
            val b = if (random.nextBoolean()) leaf(random) else Condition.And(listOf(a, leaf(random)))
            Condition.Not(a) to Condition.Not(b)
        }
        else -> condition(random, 1) to condition(random, 1)
    }

    private fun condition(random: Random, depth: Int): Condition {
        if (depth >= 4 || random.nextInt(10) < 4) {
            return leaf(random)
        }
        fun operands() = List(random.nextInt(1, 4)) { condition(random, depth + 1) }
        return when (random.nextInt(3)) {
            0 -> Condition.And(operands())
            1 -> Condition.Or(operands())
            else -> Condition.Not(condition(random, depth + 1))
        }
    }

    private fun leaf(random: Random): Condition {
        val attribute = strings.random(random).path
        fun value() = str(values.random(random))
        return when (random.nextInt(16)) {
            0, 1, 2 -> Condition.Eq(attribute, value(), random.nextBoolean())
            3, 4 -> Condition.In(attribute, List(random.nextInt(1, 4)) { value() })
            5, 6, 7 -> Condition.Prefix(attribute, value(), random.nextBoolean())
            8 -> Condition.Eq(attribute, Operand.Attr(strings.random(random).path), random.nextBoolean())
            9 -> Condition.Prefix(labels.path, str(labelLiterals.random(random)))
            10 -> Condition.Cidr(ip.path, List(random.nextInt(1, 3)) { str(blocks.random(random)) })
            11 -> addressLeaf(random)
            12 -> boolLeaf(random)
            13 -> Condition.Contains(value(), roles.path)
            14 -> time(random)
            else -> Condition.Exists(
                (strings + listOf(bool, roles, ip, labels, attr("environment.time"))).random(random).path
            )
        }
    }

    /** `eq` or `in` of addresses, which the checker turns into `cidr` of single addresses. */
    private fun addressLeaf(random: Random): Condition = if (random.nextBoolean()) {
        Condition.Eq(ip.path, str(addresses.random(random).toString()))
    } else {
        Condition.In(ip.path, List(random.nextInt(1, 3)) { str(addresses.random(random).toString()) })
    }

    /** `eq` or `in` of booleans; `in` of both is `exists`. */
    private fun boolLeaf(random: Random): Condition = if (random.nextBoolean()) {
        Condition.Eq(bool.path, Literal.Bool(random.nextBoolean()))
    } else {
        Condition.In(bool.path, List(random.nextInt(1, 3)) { Literal.Bool(random.nextBoolean()) })
    }

    private fun time(random: Random): Condition {
        val window = random.nextInt(3) == 0
        return Condition.Time(
            notBefore = instants.random(random).takeIf { random.nextInt(3) > 0 },
            notAfter = instants.random(random).takeIf { random.nextInt(3) > 0 },
            zone = zones.random(random),
            daysOfWeek = if (random.nextInt(4) == 0) listOf("SUN", "MON") else null,
            from = if (window) clock.random(random) else null,
            to = if (window) clock.random(random) else null,
        )
    }

    private fun input(random: Random): MapInput {
        fun present() = random.nextInt(10) < 8
        return MapInput(
            strings = strings.filter { present() }.associateWith { values.random(random) },
            bools = if (present()) mapOf(bool to random.nextBoolean()) else emptyMap(),
            sets = if (present()) mapOf(roles to values.filter { random.nextInt(6) == 0 }.toSet()) else emptyMap(),
            ips = if (present()) mapOf(ip to addresses.random(random)) else emptyMap(),
            time = if (present()) times.random(random) else ConditionInput.NO_TIME,
        )
    }
}
