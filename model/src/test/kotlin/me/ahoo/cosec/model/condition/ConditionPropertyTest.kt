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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * RFC 0005 §4.3: A3, normalizing does not change what a condition decides; A4, an evaluation visits no more nodes than
 * the limit. Plus: evaluation allocates nothing.
 */
class ConditionPropertyTest {
    private val strings = listOf("subject.id", "scope.space", "http.header.x-a", "resource.var.owner").map(::attr)
    private val bools = listOf("subject.authenticated", "environment.blocked").map(::attr)
    private val sets = listOf("scope.roles", "subject.roles").map(::attr)
    private val ip = attr("environment.ip")
    private val host = attr("http.origin.host")
    private val values = listOf("a", "b", "A", "", "ab")
    private val hosts = listOf("example.com", "api.example.com", "badexample.com", "EXAMPLE.org")
    private val addresses = listOf(
        "10.0.0.1",
        "10.1.0.1",
        "192.168.1.1",
        "2001:db8::1",
        "::1"
    ).map { IpAddress.parse(it)!! }

    @Test
    fun `A3 normalizing does not change decisions`() {
        val random = Random(0x5A3)
        var compiled = 0
        var rejected = 0
        repeat(3_000) {
            val condition = condition(random, 1)
            val result = ConditionChecker(zones = JavaZones).check(condition)
            if (result !is CheckResult.Valid) {
                rejected++
                return@repeat
            }
            compiled++
            val checked = result.condition
            val normal = ConditionNormalizer.normalize(checked)
            ConditionNormalizer.normalize(normal).assert().describedAs("idempotent: $checked").isEqualTo(normal)
            normal.nodes.assert().isLessThanOrEqualTo(checked.nodes)
            normal.depth.assert().isLessThanOrEqualTo(checked.depth)
            normal.cost.assert().isLessThanOrEqualTo(checked.cost)
            repeat(100) {
                val input = input(random)
                normal.evaluate(input).assert().describedAs("$checked\n$normal").isEqualTo(checked.evaluate(input))
            }
        }
        compiled.assert().isGreaterThan(1_000)
        rejected.assert().isGreaterThan(50)
    }

    @Test
    fun `A4 evaluation visits at most the node limit`() {
        val random = Random(0x5A4)
        val limits = ConditionLimits.DEFAULT
        var maxVisits = 0
        repeat(3_000) {
            val checked = (ConditionChecker(limits, JavaZones).check(condition(random, 1)) as? CheckResult.Valid)?.condition ?: return@repeat
            val normal = ConditionNormalizer.normalize(checked)
            repeat(50) {
                val input = input(random)
                for (c in listOf(checked, normal)) {
                    val visited = visits(c, input)
                    visited.assert().isLessThanOrEqualTo(c.nodes)
                    visited.assert().isLessThanOrEqualTo(limits.maxNodes)
                    maxVisits = maxOf(maxVisits, visited)
                }
            }
        }
        // The largest compiled condition visits all of its 64 nodes when nothing short-circuits.
        val widest = checked(Condition.And(List(63) { Condition.Exists("environment.time") }))
        visits(widest, MapInput(time = 0)).assert().isEqualTo(limits.maxNodes)
        maxVisits.assert().isGreaterThan(10)
    }

    @Test
    fun `normal forms ignore order, nesting and duplicates`() {
        val a = Condition.Eq("subject.id", str("a"))
        val b = Condition.Exists("scope.roles")
        val c = Condition.Prefix("scope.space", str("t"))
        fun normal(condition: Condition) = ConditionNormalizer.normalize(checked(condition))
        normal(
            Condition.And(listOf(a, Condition.And(listOf(b, c)), a))
        ).assert().isEqualTo(normal(Condition.And(listOf(c, b, a))))
        normal(
            Condition.Or(listOf(Condition.Or(listOf(a)), b, b))
        ).assert().isEqualTo(normal(Condition.Or(listOf(b, a))))
        normal(Condition.And(listOf(a, a))).assert().isEqualTo(checked(a))
        normal(Condition.Not(Condition.Not(Condition.And(listOf(b))))).assert().isEqualTo(checked(b))
        normal(Condition.Not(Condition.Not(Condition.Not(b)))).assert().isEqualTo(checked(Condition.Not(b)))
        // Unwrapping a one-operand group exposes a double negation.
        normal(Condition.Not(Condition.And(listOf(Condition.Not(b))))).assert().isEqualTo(checked(b))
        normal(Condition.Not(Condition.Or(listOf(b, a)))).canonical.assert()
            .isEqualTo("{\"not\":{\"or\":[{\"eq\":[\"subject.id\",\"a\"]},{\"exists\":\"scope.roles\"}]}}")
        // An and inside an or stays: flattening only merges the same operator.
        normal(Condition.Or(listOf(Condition.And(listOf(a, b)), c))).nodes.assert().isEqualTo(5)
        checked(a).let { ConditionNormalizer.normalize(it).assert().isSameAs(it) }
    }

    @Test
    fun `evaluation allocates nothing`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val condition = ConditionNormalizer.normalize(
            checked(
                Condition.Or(
                    listOf(
                        Condition.And(
                            listOf(
                                Condition.Eq("subject.id", str("A"), ignoreCase = true),
                                Condition.Eq("subject.authenticated", Literal.Bool(true)),
                                Condition.Eq("environment.ip", str("10.0.0.1")),
                                Condition.Eq("scope.space", Operand.Attr("subject.id"), ignoreCase = true),
                                Condition.In("scope.space", listOf(str("a"), str("b"))),
                                Condition.Contains(str("admin"), "scope.roles"),
                                Condition.Prefix("subject.id", str("a"), ignoreCase = true),
                                Condition.Prefix("http.origin.host.labels", str("com.example")),
                                Condition.Cidr("environment.ip", listOf(str("10.0.0.0/8"), str("2001:db8::/32"))),
                                Condition.Time(
                                    zone = "Europe/London",
                                    daysOfWeek = DaysOfWeek.NAMES,
                                    from = "00:00",
                                    to = "23:59:59.999999999"
                                ),
                                Condition.Exists("http.header.x-a"),
                                Condition.Not(Condition.Exists("resource.var.owner")),
                            ),
                        ),
                        Condition.Exists("environment.time"),
                    ),
                ),
            ),
        )
        val input = MapInput(
            strings = mapOf(
                attr("subject.id") to "a",
                attr("scope.space") to "a",
                host to "api.example.com",
                attr("http.header.x-a") to "1"
            ),
            bools = mapOf(attr("subject.authenticated") to true),
            sets = mapOf(attr("scope.roles") to setOf("admin")),
            ips = mapOf(ip to IpAddress.parse("10.0.0.1")!!),
            time = 1_800_000_000_000,
        )
        visits(condition, input).assert().isEqualTo(15)
        repeat(20_000) { condition.evaluate(input) }
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        var matched = 0
        repeat(100_000) { if (condition.evaluate(input)) matched++ }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        matched.assert().isEqualTo(100_000)
        // The measurement itself allocates a little; one object per evaluation would be over a megabyte.
        allocated.assert().isLessThan(10_000)
    }

    private fun condition(random: Random, depth: Int): Condition {
        val composite = depth < 10 && random.nextInt(10) < (if (depth == 1) 8 else 4)
        if (!composite) {
            return leaf(random)
        }
        fun operands() = List(random.nextInt(1, 4)) { condition(random, depth + 1) }
        return when (random.nextInt(4)) {
            0 -> Condition.And(operands())
            1 -> Condition.Or(operands())
            2 -> Condition.Not(condition(random, depth + 1))
            else -> {
                // Duplicates and nested groups of the same operator, which normalizing flattens and removes.
                val shared = condition(random, depth + 1)
                Condition.And(listOf(shared, Condition.And(listOf(shared, leaf(random)))))
            }
        }
    }

    private fun leaf(random: Random): Condition {
        val value = str(values.random(random))
        return when (random.nextInt(12)) {
            0 -> Condition.Eq(strings.random(random).path, value, random.nextBoolean())
            1 -> Condition.Eq(
                strings.random(random).path,
                Operand.Attr(strings.random(random).path),
                random.nextBoolean()
            )
            2 -> Condition.Eq(bools.random(random).path, Literal.Bool(random.nextBoolean()))
            3 -> Condition.In(strings.random(random).path, List(random.nextInt(1, 4)) { str(values.random(random)) })
            4 -> Condition.Contains(value, sets.random(random).path)
            5 -> Condition.Prefix(strings.random(random).path, value, random.nextBoolean())
            6 -> Condition.Prefix(
                "http.origin.host.labels",
                str(listOf("com", "com.example", "org.example").random(random))
            )
            7 -> Condition.Cidr(
                ip.path,
                List(
                    random.nextInt(1, 3)
                ) { str(listOf("10.0.0.0/8", "192.168.0.0/16", "2001:db8::/32", "::1").random(random)) }
            )
            8 -> Condition.Eq(ip.path, str(addresses.random(random).toString()))
            9 -> Condition.Time(
                zone = "Asia/Shanghai",
                daysOfWeek = listOf("MON", "WED", "SAT").shuffled(random).take(random.nextInt(1, 4))
            )
            10 -> Condition.Not(
                Condition.Not(Condition.Exists(listOf(strings, bools, sets).random(random).random(random).path))
            )
            else -> Condition.Exists(
                listOf(ip, host, attr("http.origin.host.labels"), attr("environment.time")).random(random).path
            )
        }
    }

    private fun input(random: Random): MapInput {
        fun present() = random.nextInt(10) < 7
        return MapInput(
            strings = (strings.filter { present() }.associateWith { values.random(random) }) +
                (if (present()) mapOf(host to hosts.random(random)) else emptyMap()),
            bools = bools.filter { present() }.associateWith { random.nextBoolean() },
            sets = sets.filter { present() }.associateWith { values.filter { random.nextBoolean() }.toSet() },
            ips = if (present()) mapOf(ip to addresses.random(random)) else emptyMap(),
            time = if (present()) random.nextLong(0, 4_000_000_000_000) else ConditionInput.NO_TIME,
        )
    }
}
