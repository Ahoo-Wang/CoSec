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

/** What each operator decides, absent attributes included (RFC 0005 §3.3, §3.4). */
class ConditionEvaluationTest {
    private val id = attr("subject.id")
    private val owner = attr("resource.attributes.owner")
    private val header = attr("http.header.x-debug")
    private val authenticated = attr("subject.authenticated")
    private val blocked = attr("environment.blocked")
    private val ip = attr("environment.ip")
    private val roles = attr("scope.roles")
    private val origin = attr("http.origin.host")

    private val empty = MapInput()

    private fun eval(condition: Condition, input: ConditionInput): Boolean = checked(condition).evaluate(input)

    @Test
    fun `eq compares strings exactly or folding ASCII case`() {
        val alice = MapInput(strings = mapOf(id to "Alice"))
        eval(Condition.Eq("subject.id", str("Alice")), alice).assert().isTrue()
        eval(Condition.Eq("subject.id", str("alice")), alice).assert().isFalse()
        eval(Condition.Eq("subject.id", str("aLICE"), ignoreCase = true), alice).assert().isTrue()
        eval(Condition.Eq("subject.id", str("alic"), ignoreCase = true), alice).assert().isFalse()
        // ASCII only: the Kelvin sign is not a K.
        eval(
            Condition.Eq("subject.id", str("k"), ignoreCase = true),
            MapInput(strings = mapOf(id to "K"))
        ).assert().isFalse()
        eval(
            Condition.Eq("subject.id", str("é"), ignoreCase = true),
            MapInput(strings = mapOf(id to "É"))
        ).assert().isFalse()
        eval(Condition.Eq("subject.id", str("")), MapInput(strings = mapOf(id to ""))).assert().isTrue()
    }

    @Test
    fun `eq compares bools and addresses`() {
        eval(
            Condition.Eq("subject.authenticated", Literal.Bool(true)),
            MapInput(bools = mapOf(authenticated to true))
        ).assert().isTrue()
        eval(
            Condition.Eq("subject.authenticated", Literal.Bool(true)),
            MapInput(bools = mapOf(authenticated to false))
        ).assert().isFalse()
        eval(
            Condition.Eq("subject.authenticated", Literal.Bool(false)),
            MapInput(bools = mapOf(authenticated to false))
        ).assert().isTrue()
        val client = MapInput(ips = mapOf(ip to IpAddress.parse("10.0.0.1")!!))
        eval(Condition.Eq("environment.ip", str("::ffff:10.0.0.1")), client).assert().isTrue()
        eval(Condition.Eq("environment.ip", str("10.0.0.2")), client).assert().isFalse()
    }

    @Test
    fun `eq of two attributes`() {
        val same = MapInput(strings = mapOf(id to "a", owner to "a"))
        val differ = MapInput(strings = mapOf(id to "a", owner to "A"))
        eval(Condition.Eq("resource.attributes.owner", Operand.Attr("subject.id")), same).assert().isTrue()
        eval(Condition.Eq("resource.attributes.owner", Operand.Attr("subject.id")), differ).assert().isFalse()
        eval(
            Condition.Eq("resource.attributes.owner", Operand.Attr("subject.id"), ignoreCase = true),
            differ
        ).assert().isTrue()
        eval(
            Condition.Eq("subject.authenticated", Operand.Attr("environment.blocked")),
            MapInput(bools = mapOf(authenticated to true, blocked to true))
        )
            .assert().isTrue()
        eval(
            Condition.Eq("subject.authenticated", Operand.Attr("environment.blocked")),
            MapInput(bools = mapOf(authenticated to true, blocked to false))
        )
            .assert().isFalse()
        val ips = attr("environment.ip")
        eval(
            Condition.Eq("environment.ip", Operand.Attr("environment.ip")),
            MapInput(ips = mapOf(ips to IpAddress.parse("::1")!!))
        ).assert().isTrue()
    }

    /** Q7: absent is not equal to anything, another absent value and the empty string included (D2). */
    @Test
    fun `absent is equal to nothing`() {
        val ownerIsSubject = Condition.Eq("resource.attributes.owner", Operand.Attr("subject.id"))
        eval(ownerIsSubject, empty).assert().isFalse()
        eval(ownerIsSubject, MapInput(strings = mapOf(id to ""))).assert().isFalse()
        eval(ownerIsSubject, MapInput(strings = mapOf(owner to ""))).assert().isFalse()
        eval(ownerIsSubject, MapInput(strings = mapOf(owner to "", id to ""))).assert().isTrue()
        eval(Condition.Eq("subject.authenticated", Operand.Attr("environment.blocked")), empty).assert().isFalse()
        eval(
            Condition.Eq("subject.authenticated", Operand.Attr("environment.blocked")),
            MapInput(bools = mapOf(blocked to false))
        ).assert().isFalse()
        eval(Condition.Eq("environment.ip", Operand.Attr("environment.ip")), empty).assert().isFalse()
        eval(Condition.Eq("http.header.X-Debug", str("")), empty).assert().isFalse()
        eval(Condition.Eq("subject.authenticated", Literal.Bool(false)), empty).assert().isFalse()
        eval(Condition.Eq("environment.ip", str("10.0.0.1")), empty).assert().isFalse()
    }

    @Test
    fun `absent makes every comparison false and not negates it`() {
        val leaves = listOf(
            Condition.Eq("http.header.x-debug", str("1")),
            Condition.Eq("http.header.x-debug", str("1"), ignoreCase = true),
            Condition.In("http.header.x-debug", listOf(str("1"), str(""))),
            Condition.Contains(str("admin"), "scope.roles"),
            Condition.Prefix("http.header.x-debug", str("")),
            Condition.Prefix("http.header.x-debug", str(""), ignoreCase = true),
            Condition.Prefix("http.origin.host.labels", str("com")),
            Condition.Cidr("environment.ip", listOf(str("0.0.0.0/0"), str("::/0"))),
            Condition.Exists("http.header.x-debug"),
            Condition.Exists("http.origin.host.labels"),
            Condition.Exists("subject.authenticated"),
            Condition.Exists("scope.roles"),
            Condition.Exists("environment.ip"),
            Condition.Exists("environment.time"),
        )
        for (leaf in leaves) {
            eval(leaf, empty).assert().describedAs(checked(leaf).canonical).isFalse()
            eval(Condition.Not(leaf), empty).assert().describedAs(checked(leaf).canonical).isTrue()
        }
    }

    @Test
    fun `exists sees present values, empty ones included`() {
        eval(Condition.Exists("http.header.x-debug"), MapInput(strings = mapOf(header to ""))).assert().isTrue()
        eval(Condition.Exists("scope.roles"), MapInput(sets = mapOf(roles to emptySet()))).assert().isTrue()
        eval(Condition.Exists("http.origin.host.labels"), MapInput(strings = mapOf(origin to ""))).assert().isTrue()
        eval(Condition.Exists("environment.time"), MapInput(time = 0)).assert().isTrue()
        eval(
            Condition.Exists("subject.authenticated"),
            MapInput(bools = mapOf(authenticated to false))
        ).assert().isTrue()
        eval(Condition.Exists("environment.ip"), MapInput(ips = mapOf(ip to IpAddress.parse("::")!!))).assert().isTrue()
    }

    @Test
    fun `in tests membership both ways`() {
        val teamA = MapInput(strings = mapOf(attr("scope.space") to "team-a"))
        eval(Condition.In("scope.space", listOf(str("team-a"), str("team-b"))), teamA).assert().isTrue()
        eval(Condition.In("scope.space", listOf(str("team-c"))), teamA).assert().isFalse()
        val admin = MapInput(sets = mapOf(roles to setOf("admin", "dev")))
        eval(Condition.Contains(str("admin"), "scope.roles"), admin).assert().isTrue()
        eval(Condition.Contains(str("ops"), "scope.roles"), admin).assert().isFalse()
        eval(
            Condition.In("subject.authenticated", listOf(Literal.Bool(true))),
            MapInput(bools = mapOf(authenticated to true))
        ).assert().isTrue()
        eval(
            Condition.In("subject.authenticated", listOf(Literal.Bool(false))),
            MapInput(bools = mapOf(authenticated to true))
        ).assert().isFalse()
        eval(
            Condition.In("subject.authenticated", listOf(Literal.Bool(false), Literal.Bool(true))),
            MapInput(bools = mapOf(authenticated to true))
        )
            .assert().isTrue()
        val client = MapInput(ips = mapOf(ip to IpAddress.parse("2001:db8::1")!!))
        eval(Condition.In("environment.ip", listOf(str("10.0.0.1"), str("2001:DB8::1"))), client).assert().isTrue()
        eval(Condition.In("environment.ip", listOf(str("2001:db8::2"))), client).assert().isFalse()
    }

    @Test
    fun `prefix compares strings`() {
        val path = MapInput(strings = mapOf(attr("resource.id") to "/Orders/1"))
        eval(Condition.Prefix("resource.id", str("/Orders/")), path).assert().isTrue()
        eval(Condition.Prefix("resource.id", str("/orders/")), path).assert().isFalse()
        eval(Condition.Prefix("resource.id", str("/ORDERS/"), ignoreCase = true), path).assert().isTrue()
        eval(Condition.Prefix("resource.id", str("/Orders/1/2")), path).assert().isFalse()
        eval(Condition.Prefix("resource.id", str("/Orders/1/2"), ignoreCase = true), path).assert().isFalse()
        eval(Condition.Prefix("resource.id", str("")), path).assert().isTrue()
    }

    @Test
    fun `labels match whole labels of the host`() {
        val example = Condition.Prefix("http.origin.host.labels", str("com.Example"))
        mapOf(
            "example.com" to true, "api.example.com" to true, "API.EXAMPLE.COM" to true, "a.b.example.com" to true,
            ".example.com" to true, "badexample.com" to false, "example.com.evil" to false, "example.co" to false,
            "com" to false, "example.com." to false, "" to false, "xexample.com" to false,
        ).forEach { (host, expected) ->
            eval(example, MapInput(strings = mapOf(origin to host))).assert().describedAs(host).isEqualTo(expected)
        }
        eval(
            Condition.Prefix("http.referer.host.labels", str("com")),
            MapInput(strings = mapOf(attr("http.referer.host") to "x.com"))
        ).assert().isTrue()
        eval(
            Condition.Prefix("http.referer.host.labels", str("com")),
            MapInput(strings = mapOf(origin to "x.com"))
        ).assert().isFalse()
    }

    @Test
    fun `cidr matches either family`() {
        val blocks = Condition.Cidr("environment.ip", listOf(str("10.0.0.0/8"), str("2001:db8::/32")))
        mapOf(
            "10.1.2.3" to true,
            "::ffff:10.1.2.3" to true,
            "11.0.0.0" to false,
            "2001:db8:ffff::1" to true,
            "2001:db9::" to false,
            "::a01:203" to false
        )
            .forEach { (address, expected) ->
                eval(
                    blocks,
                    MapInput(ips = mapOf(ip to IpAddress.parse(address)!!))
                ).assert().describedAs(address).isEqualTo(expected)
            }
    }

    @Test
    fun `and and or short-circuit from left to right`() {
        val yes = Condition.Exists("environment.time")
        val no = Condition.Exists("http.header.x-debug")
        val input = MapInput(time = 0)
        eval(Condition.And(listOf(yes, yes)), input).assert().isTrue()
        eval(Condition.And(listOf(yes, no)), input).assert().isFalse()
        eval(Condition.Or(listOf(no, yes)), input).assert().isTrue()
        eval(Condition.Or(listOf(no, no)), input).assert().isFalse()
        visits(checked(Condition.And(listOf(no, yes, yes))), input).assert().isEqualTo(2)
        visits(checked(Condition.Or(listOf(yes, no, no))), input).assert().isEqualTo(2)
        visits(checked(Condition.Not(Condition.Or(listOf(no, yes, no)))), input).assert().isEqualTo(4)
    }

    @Test
    fun `the example of the RFC`() {
        val condition = checked(
            Condition.And(
                listOf(
                    Condition.Eq("subject.authenticated", Literal.Bool(true)),
                    Condition.In("scope.space", listOf(str("team-a"), str("team-b"))),
                    Condition.Not(Condition.Cidr("environment.ip", listOf(str("10.0.0.0/8")))),
                    Condition.Eq("resource.var.ownerId", Operand.Attr("subject.id")),
                    Condition.Time(
                        notAfter = "2026-12-31T00:00:00Z",
                        zone = "Asia/Shanghai",
                        daysOfWeek = listOf("MON", "FRI"),
                        from = "09:00",
                        to = "18:00"
                    ),
                ),
            ),
        )
        listOf(condition.nodes, condition.depth, condition.cost).assert().containsExactly(7, 3, 1 + 2 + 4 + 5 + 2 + 8)
        val monday = java.time.ZonedDateTime.parse(
            "2026-10-26T10:00:00+08:00[Asia/Shanghai]"
        ).toInstant().toEpochMilli()
        fun request(time: Long, address: String) = MapInput(
            strings = mapOf(attr("scope.space") to "team-a", attr("resource.var.ownerId") to "alice", id to "alice"),
            bools = mapOf(authenticated to true),
            ips = mapOf(ip to IpAddress.parse(address)!!),
            time = time,
        )
        condition.evaluate(request(monday, "192.168.0.1")).assert().isTrue()
        condition.evaluate(request(monday, "10.0.0.1")).assert().isFalse()
        condition.evaluate(request(monday + 86_400_000, "192.168.0.1")).assert().isFalse()
        condition.evaluate(request(monday + 9 * 3_600_000, "192.168.0.1")).assert().isFalse()
    }

    @Test
    fun `canonical forms are readable and decide equality`() {
        checked(Condition.Eq("http.header.X-A", str("V\"\\\u0001"), ignoreCase = true)).canonical
            .assert().isEqualTo("{\"eq\":[\"http.header.x-a\",\"v\\\"\\\\\\u0001\"],\"ignoreCase\":true}")
        checked(
            Condition.In("scope.space", listOf(str("b"), str("a"), str("b")))
        ).toString().assert().isEqualTo("{\"in\":[\"scope.space\",[\"a\",\"b\"]]}")
        checked(
            Condition.Contains(str("admin"), "scope.roles")
        ).canonical.assert().isEqualTo("{\"in\":[\"admin\",\"scope.roles\"]}")
        checked(Condition.Cidr("environment.ip", listOf(str("::/0"), str("10.1.0.0/8")))).canonical
            .assert().isEqualTo("{\"cidr\":[\"environment.ip\",[\"10.0.0.0/8\",\"::/0\"]]}")
        checked(
            Condition.Time(
                notBefore = "1970-01-01T00:00:01Z",
                notAfter = "1970-01-01T00:00:02Z",
                daysOfWeek = listOf("fri", "MON"),
                from = "00:00",
                to = "00:00:01"
            )
        )
            .canonical.assert()
            .isEqualTo(
                "{\"time\":{\"notBefore\":1000,\"notAfter\":2000,\"zone\":\"UTC\",\"daysOfWeek\":[\"MONDAY\",\"FRIDAY\"],\"from\":0,\"to\":1000000000}}"
            )
        checked(
            Condition.Not(Condition.Exists("environment.ip"))
        ).canonical.assert().isEqualTo("{\"not\":{\"exists\":\"environment.ip\"}}")
        checked(Condition.Eq("subject.id", Operand.Attr("resource.var.x"))).canonical
            .assert().isEqualTo("{\"eq\":[\"subject.id\",{\"attr\":\"resource.var.x\"}]}")
        checked(Condition.Prefix("http.origin.host.labels", str("COM.Example"))).canonical
            .assert().isEqualTo("{\"prefix\":[\"http.origin.host.labels\",\"com.example\"]}")
        val a = checked(Condition.In("scope.space", listOf(str("a"), str("b"))))
        val b = checked(Condition.In("scope.space", listOf(str("b"), str("a"))))
        a.assert().isEqualTo(b)
        a.hashCode().assert().isEqualTo(b.hashCode())
        a.equals(a.canonical).assert().isFalse()
    }
}
