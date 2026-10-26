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
 * RFC 0005 §4.3: A1, index keys never exclude a request the condition matches; A2, `implies` is sound. The oracle is
 * plain evaluation over many sampled requests, drawn from small value pools so that literals, case variants,
 * prefixes, nested blocks and period bounds collide often.
 */
class AnalysisPropertyTest {
    private val strings = listOf("subject.id", "scope.space", "http.origin.host").map(::attr)
    private val bool = attr("subject.authenticated")
    private val roles = attr("scope.roles")
    private val ip = attr("environment.ip")
    private val labels = attr("http.origin.host.labels")
    private val values = listOf("a", "A", "ab", "Ab", "aB", "abc", "b", "")
    private val hosts = listOf("example.com", "api.example.com", "a.api.example.com", "badexample.com", "EXAMPLE.com")
    private val labelLiterals = listOf("com", "com.example", "com.example.api", "org")
    private val blocks =
        listOf("10.0.0.0/8", "10.1.0.0/16", "10.1.2.3", "10.1.2.4", "192.168.0.0/16", "2001:db8::/32", "2001:db8::1")
    private val addresses = listOf(
        "10.1.2.3",
        "10.1.2.4",
        "10.2.0.1",
        "192.168.1.1",
        "11.0.0.1",
        "2001:db8::1",
        "2001:db9::1"
    )
        .map { IpAddress.parse(it)!! }
    private val instants =
        listOf("2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z")
    private val times =
        listOf(
            1_767_225_599_999L,
            1_767_225_600_000L,
            1_780_000_000_000L,
            1_780_272_000_000L,
            1_798_761_600_000L,
            1_830_297_600_000L
        )

    @Test
    fun `A1 index keys admit every request the condition matches`() {
        val random = Random(0x5A1)
        var matched = 0
        var constrained = 0
        repeat(4_000) {
            val condition = checkedOrNull(condition(random, 1)) ?: return@repeat
            val keys = strings.associateWith { IndexKeys.of(condition, it) }
            if (keys.values.any { it is IndexKeys.Only }) constrained++
            repeat(100) {
                val input = input(random)
                if (condition.evaluate(input)) {
                    matched++
                    for ((attribute, key) in keys) {
                        key.admits(input.strings[attribute]).assert()
                            .describedAs("$condition matches ${input.strings} outside $key").isTrue()
                    }
                }
            }
        }
        constrained.assert().isGreaterThan(700)
        matched.assert().isGreaterThan(20_000)
    }

    @Test
    fun `A2 implies is sound`() {
        val random = Random(0x5A2)
        var yes = 0
        var refuted = 0
        val strategies = IntArray(STRATEGIES)
        repeat(30_000) {
            val strategy = random.nextInt(STRATEGIES)
            val (left, right) = pair(random, strategy)
            val c1 = checkedOrNull(left) ?: return@repeat
            val c2 = checkedOrNull(right) ?: return@repeat
            val proven = ConditionContainment.implies(c1, c2) == Containment.YES
            if (proven) {
                yes++
                strategies[strategy]++
            }
            var counterexample = false
            repeat(if (proven) 400 else 50) {
                val input = input(random)
                if (c1.evaluate(input) && !c2.evaluate(input)) {
                    counterexample = true
                    proven.assert().describedAs("implies($c1, $c2) = YES, but not for $input").isFalse()
                }
            }
            if (counterexample) refuted++
        }
        // Each strategy reaches YES, and the oracle has teeth: it refutes many of the pairs that are not contained.
        strategies.forEachIndexed { i, count -> count.assert().describedAs("strategy $i").isGreaterThan(100) }
        yes.assert().isGreaterThan(6_000)
        refuted.assert().isGreaterThan(5_000)
    }

    private fun checkedOrNull(condition: Condition): CheckedCondition? =
        (ConditionChecker(zones = JavaZones).check(condition) as? CheckResult.Valid)?.condition

    /** Pairs biased towards containment, so that the YES paths are exercised and not only UNKNOWN. */
    private fun pair(random: Random, strategy: Int): Pair<Condition, Condition> = when (strategy) {
        // The granter's condition AND an extra condition (RFC 0001 §7.1).
        0 -> condition(random, 2).let { Condition.And(listOf(condition(random, 2), it)) to it }
        // One side of a disjunction.
        1 -> condition(random, 2).let { it to Condition.Or(listOf(condition(random, 3), it)) }
        // Two atoms, often on the same attribute.
        2 -> leaf(random) to leaf(random)
        // Contraposition: of two atoms, or of a condition and a narrower one.
        3 -> leaf(random).let { a ->
            val b = if (random.nextBoolean()) leaf(random) else Condition.And(listOf(a, leaf(random)))
            Condition.Not(a) to Condition.Not(b)
        }
        // Anything.
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
        return when (random.nextInt(14)) {
            0, 1 -> Condition.Eq(attribute, value(), random.nextBoolean())
            2, 3 -> Condition.In(attribute, List(random.nextInt(1, 4)) { value() })
            4, 5 -> Condition.Prefix(attribute, value(), random.nextBoolean())
            6 -> Condition.Eq(attribute, Operand.Attr(strings.random(random).path), random.nextBoolean())
            7 -> Condition.Prefix(labels.path, str(labelLiterals.random(random)))
            8 -> Condition.Cidr(ip.path, List(random.nextInt(1, 3)) { str(blocks.random(random)) })
            9 -> addressLeaf(random)
            10 -> boolLeaf(random)
            11 -> Condition.Contains(value(), roles.path)
            12 -> time(random)
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
        val (notBefore, notAfter) = instants.shuffled(random).take(2).sorted()
        return Condition.Time(
            notBefore = notBefore.takeIf { random.nextInt(4) > 0 },
            notAfter = notAfter.takeIf { random.nextInt(4) > 0 },
            zone = listOf("UTC", "+08:00").random(random),
            daysOfWeek = if (random.nextInt(4) == 0) listOf("MON", "THU") else null,
        )
    }

    private fun input(random: Random): MapInput {
        fun present() = random.nextInt(10) < 8
        val strings = this.strings.filter { present() }.associateWith {
            if (it.path == "http.origin.host" && random.nextBoolean()) hosts.random(random) else values.random(random)
        }
        return MapInput(
            strings = strings,
            bools = if (present()) mapOf(bool to random.nextBoolean()) else emptyMap(),
            sets = if (present()) mapOf(roles to values.filter { random.nextInt(3) == 0 }.toSet()) else emptyMap(),
            ips = if (present()) mapOf(ip to addresses.random(random)) else emptyMap(),
            time = if (present()) times.random(random) + random.nextLong(-1, 2) else ConditionInput.NO_TIME,
        )
    }

    private companion object {
        const val STRATEGIES = 5
    }
}
