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

/** Type rules and cost bounds (RFC 0005 §3.2, §3.5): every error names its JSON path. */
class ConditionCheckerTest {
    private fun single(
        condition: Condition,
        checker: ConditionChecker = ConditionChecker(zones = JavaZones)
    ): ConditionError =
        errors(condition, checker).single()

    @Test
    fun `unknown attributes do not compile`() {
        single(
            Condition.Exists("subject.name")
        ).assert().isEqualTo(ConditionError("$.exists", "unknown attribute `subject.name`"))
        single(Condition.Eq("subject.id", Operand.Attr("request.path"))).path.assert().isEqualTo("$.eq[1].attr")
        single(
            Condition.And(listOf(Condition.Exists("subject.id"), Condition.Not(Condition.Prefix("x", str("a")))))
        ).path
            .assert().isEqualTo("$.and[1].not.prefix[0]")
        single(Condition.Contains(str("a"), "roles")).path.assert().isEqualTo("$.in[1]")
        single(Condition.Cidr("ip", listOf(str("10.0.0.0/8")))).path.assert().isEqualTo("$.cidr[0]")
        single(Condition.In("space", listOf(str("a")))).path.assert().isEqualTo("$.in[0]")
    }

    /** `CND-5`: a condition on a credential header would reveal it through decisions. */
    @Test
    fun `credential headers are refused`() {
        listOf("Authorization", "proxy-authorization", "Cookie", "set-cookie", "X-API-Key").forEach { header ->
            single(Condition.Prefix("http.header.$header", str("Bearer a"))).assert()
                .isEqualTo(
                    ConditionError(
                        "$.prefix[0]",
                        "`http.header.$header` is a credential header, which no attribute reference may read"
                    )
                )
        }
        checked(Condition.Exists("http.header.x-debug"))
    }

    /** `CND-5` for every reference, not only conditions: a rate limit key resolves through [ConditionChecker.attribute]. */
    @Test
    fun `no attribute reference resolves a credential header`() {
        val checker = ConditionChecker()
        listOf("Authorization", "cosec-identity", "X-API-Key").forEach { header ->
            checker.attribute("http.header.$header").assert().isNull()
            checker.unresolved("http.header.$header").assert().contains("credential header")
        }
        checker.attribute("http.header.x-api-\u212Aey").assert().isNull()
        checker.unresolved("http.header.x-api-\u212Aey").assert().contains("not a header name")
        checker.attribute("http.header.x-debug").assert().isNotNull()
        checker.unresolved("http.header.").assert().isEqualTo("unknown attribute `http.header.`")
        checker.unresolved("ext.geo.country").assert().contains("not declared")
    }

    @Test
    fun `eq needs operands of one type`() {
        single(Condition.Eq("subject.authenticated", str("true"))).assert()
            .isEqualTo(ConditionError("$.eq[1]", "a String does not compare with subject.authenticated (Bool)"))
        single(
            Condition.Eq("subject.id", Literal.Bool(true))
        ).message.assert().isEqualTo("a Bool does not compare with subject.id (String)")
        single(Condition.Eq("subject.id", Operand.Attr("subject.authenticated"))).message
            .assert().isEqualTo("subject.authenticated (Bool) does not compare with subject.id (String)")
        single(
            Condition.Eq("scope.roles", Operand.Attr("subject.roles"))
        ).message.assert().isEqualTo("`eq` does not apply to scope.roles (Set<String>)")
        single(Condition.Eq("environment.time", str("2026-01-01T00:00:00Z"))).message
            .assert().isEqualTo("a String does not compare with environment.time (Instant)")
        single(
            Condition.Eq("http.origin.host.labels", str("com"))
        ).message.assert().isEqualTo("a String does not compare with http.origin.host.labels (Labels)")
        single(
            Condition.Eq("environment.ip", str("10.0.0"))
        ).assert().isEqualTo(ConditionError("$.eq[1]", "is not an IP address: `10.0.0`"))
    }

    @Test
    fun `ignoreCase applies to strings only`() {
        single(Condition.Eq("subject.authenticated", Literal.Bool(true), ignoreCase = true)).assert()
            .isEqualTo(
                ConditionError("$.ignoreCase", "applies to String attributes only, not subject.authenticated (Bool)")
            )
        single(
            Condition.Eq("environment.ip", Operand.Attr("environment.ip"), ignoreCase = true)
        ).path.assert().isEqualTo("$.ignoreCase")
        single(
            Condition.Prefix("http.origin.host.labels", str("com"), ignoreCase = true)
        ).path.assert().isEqualTo("$.ignoreCase")
    }

    @Test
    fun `in needs values of the attribute's type or a set attribute`() {
        single(
            Condition.In("scope.space", emptyList())
        ).assert().isEqualTo(ConditionError("$.in[1]", "needs at least one value"))
        single(Condition.In("scope.space", listOf(str("a"), Literal.Bool(true)))).path.assert().isEqualTo("$.in[1][1]")
        single(
            Condition.In("subject.authenticated", listOf(Literal.Bool(true), str("x")))
        ).path.assert().isEqualTo("$.in[1][1]")
        single(
            Condition.In("environment.ip", listOf(str("10.0.0.1"), str("x")))
        ).assert().isEqualTo(ConditionError("$.in[1][1]", "is not an IP address: `x`"))
        single(
            Condition.In("environment.ip", listOf(str("10.0.0.1"), Literal.Bool(false)))
        ).path.assert().isEqualTo("$.in[1][1]")
        single(
            Condition.In("scope.roles", listOf(str("admin")))
        ).message.assert().isEqualTo("`in` does not apply to scope.roles (Set<String>)")
        single(Condition.Contains(str("admin"), "scope.space")).message
            .assert().isEqualTo("scope.space is String; `in` with the value first needs a Set<String> attribute")
        single(Condition.Contains(Literal.Bool(true), "scope.roles")).path.assert().isEqualTo("$.in[0]")
    }

    @Test
    fun `in on bools and addresses become eq, exists and cidr`() {
        checked(
            Condition.In("subject.authenticated", listOf(Literal.Bool(true), Literal.Bool(true)))
        ).assert().isInstanceOf(CheckedCondition.BoolEq::class.java)
        checked(
            Condition.In("subject.authenticated", listOf(Literal.Bool(true), Literal.Bool(false)))
        ).assert().isInstanceOf(CheckedCondition.Exists::class.java)
        checked(Condition.In("environment.ip", listOf(str("10.0.0.1"), str("::1")))).canonical
            .assert().isEqualTo("{\"cidr\":[\"environment.ip\",[\"10.0.0.1/32\",\"::1/128\"]]}")
    }

    @Test
    fun `prefix applies to strings and labels`() {
        single(
            Condition.Prefix("environment.ip", str("10."))
        ).assert().isEqualTo(ConditionError("$", "`prefix` does not apply to environment.ip (Ip)"))
        single(Condition.Prefix("subject.id", Literal.Bool(true))).path.assert().isEqualTo("$.prefix[1]")
        listOf("", ".com", "com.", "com..example").forEach {
            single(Condition.Prefix("http.origin.host.labels", str(it))).message.assert().describedAs(it)
                .isEqualTo("is not a list of labels such as `com.example`: labels must not be empty")
        }
    }

    @Test
    fun `cidr needs blocks and an address attribute`() {
        single(
            Condition.Cidr("subject.id", listOf(str("10.0.0.0/8")))
        ).message.assert().isEqualTo("`cidr` does not apply to subject.id (String)")
        single(
            Condition.Cidr("environment.ip", emptyList())
        ).assert().isEqualTo(ConditionError("$.cidr[1]", "needs at least one block"))
        single(Condition.Cidr("environment.ip", listOf(str("10.0.0.0/8"), str("10.0.0.0/33")))).assert()
            .isEqualTo(ConditionError("$.cidr[1][1]", "is not a CIDR block: `10.0.0.0/33`"))
        single(Condition.Cidr("environment.ip", listOf(Literal.Bool(true)))).path.assert().isEqualTo("$.cidr[1][0]")
    }

    @Test
    fun `time is validated as in 7x`() {
        fun time(condition: Condition.Time) = errors(condition).map { it.path to it.message }
        time(
            Condition.Time()
        ).assert().containsExactly("$.time" to "needs at least one of notBefore, notAfter, daysOfWeek or from/to")
        time(Condition.Time(from = "09:00")).assert().containsExactly("$.time" to "needs both from and to, or neither")
        time(Condition.Time(to = "09:00", daysOfWeek = listOf("MON"))).assert()
            .containsExactly("$.time" to "needs both from and to, or neither")
        time(
            Condition.Time(from = "09:00", to = "09:00")
        ).assert().containsExactly("$.time.to" to "must differ from `from`: the window would be empty")
        time(Condition.Time(notBefore = "2026-01-02T00:00Z", notAfter = "2026-01-01T00:00Z")).assert()
            .containsExactly("$.time.notAfter" to "must be after notBefore")
        time(
            Condition.Time(notBefore = "2026-01-01T00:00Z", notAfter = "2026-01-01T00:00Z")
        ).single().first.assert().isEqualTo("$.time.notAfter")
        time(
            Condition.Time(daysOfWeek = emptyList())
        ).assert().containsExactly("$.time.daysOfWeek" to "needs at least one day")
        time(
            Condition.Time(daysOfWeek = listOf("MON", "Funday"))
        ).assert().containsExactly("$.time.daysOfWeek[1]" to "is not a day of the week: `Funday`")
        time(
            Condition.Time(notBefore = "tomorrow", notAfter = "2026", from = "9am", to = "18:00")
        ).map { it.first }.assert()
            .containsExactly("$.time.notBefore", "$.time.notAfter", "$.time.from")
        time(Condition.Time(zone = "Mars/Olympus", daysOfWeek = listOf("MON"))).assert()
            .containsExactly("$.time.zone" to "unknown time zone `Mars/Olympus`; region zones need the host's resolver")
        // Without the host's resolver, only fixed zones compile.
        errors(
            Condition.Time(zone = "Asia/Shanghai", daysOfWeek = listOf("MON")),
            ConditionChecker()
        ).single().path.assert().isEqualTo("$.time.zone")
        checked(
            Condition.Time(zone = "+08:00", daysOfWeek = listOf("MON")),
            ConditionChecker()
        ).assert().isInstanceOf(CheckedCondition.Time::class.java)
    }

    @Test
    fun `and and or need operands, and every error is reported`() {
        single(Condition.And(emptyList())).assert().isEqualTo(ConditionError("$.and", "needs at least one condition"))
        single(Condition.Or(emptyList())).assert().isEqualTo(ConditionError("$.or", "needs at least one condition"))
        errors(
            Condition.Or(listOf(Condition.Exists("a"), Condition.Exists("subject.id"), Condition.Exists("b")))
        ).map { it.path }
            .assert().containsExactly("$.or[0].exists", "$.or[2].exists")
    }

    @Test
    fun `the default limits are the decided values`() {
        with(ConditionLimits.DEFAULT) {
            listOf(
                maxNodes,
                maxDepth,
                maxInValues,
                maxCidrBlocks,
                maxLiteralBytes,
                maxCost
            ).assert().containsExactly(64, 8, 1024, 256, 1024, 256)
        }
        with(CheckedCondition.Weights) {
            listOf(AND, OR, NOT, EXISTS, EQ, PREFIX, IN, CIDR, TIME).assert().containsExactly(1, 1, 1, 1, 2, 2, 4, 4, 8)
        }
        runCatching { ConditionLimits(maxNodes = 0) }.isFailure.assert().isTrue()
        runCatching { ConditionLimits(maxCost = 0) }.isFailure.assert().isTrue()
    }

    @Test
    fun `node and depth limits stop the checker early`() {
        val exists = Condition.Exists("subject.id")
        checked(Condition.And(List(63) { exists })).nodes.assert().isEqualTo(64)
        single(
            Condition.And(List(64) { exists })
        ).assert().isEqualTo(ConditionError("$.and[63]", "has more than 64 nodes"))
        // A million operands are not each checked: the checker stops at the limit.
        single(Condition.Or(List(1_000_000) { exists })).path.assert().isEqualTo("$.or[63]")

        fun nested(depth: Int): Condition = (2..depth).fold(exists as Condition) { inner, _ -> Condition.Not(inner) }
        checked(nested(8)).depth.assert().isEqualTo(8)
        single(
            nested(9)
        ).assert().isEqualTo(ConditionError("$.not.not.not.not.not.not.not.not", "is nested deeper than 8"))
        // Deep enough to overflow a recursive checker: it never descends past the limit.
        single(nested(100_000)).message.assert().isEqualTo("is nested deeper than 8")
    }

    @Test
    fun `size limits on literals, in and cidr`() {
        checked(Condition.In("scope.space", List(1024) { str("v$it") }))
        single(
            Condition.In("scope.space", List(1025) { str("v$it") })
        ).assert().isEqualTo(ConditionError("$.in[1]", "has 1025 values, more than 1024"))
        checked(Condition.Cidr("environment.ip", List(256) { str("10.0.$it.0/24") }))
        single(
            Condition.Cidr("environment.ip", List(257) { str("10.${it / 256}.${it % 256}.0/24") })
        ).message.assert().isEqualTo("has 257 blocks, more than 256")
        checked(Condition.Eq("subject.id", str("a".repeat(1024))))
        single(
            Condition.Eq("subject.id", str("a".repeat(1025)))
        ).assert().isEqualTo(ConditionError("$.eq[1]", "is 1025 bytes long, more than 1024"))
        // Bytes, not characters.
        single(
            Condition.Prefix("subject.id", str("é".repeat(513)))
        ).message.assert().isEqualTo("is 1026 bytes long, more than 1024")
        single(Condition.In("scope.space", listOf(str("中".repeat(342))))).path.assert().isEqualTo("$.in[1][0]")
        single(
            Condition.Contains(str("😀".repeat(257)), "scope.roles")
        ).message.assert().isEqualTo("is 1028 bytes long, more than 1024")
        single(
            Condition.Cidr("environment.ip", listOf(str("1".repeat(2000))))
        ).message.assert().isEqualTo("is 2000 bytes long, more than 1024")
        listOf(
            "a",
            "é",
            "中",
            "😀",
            "\uD800",
            "\uD800x"
        ).map { ConditionChecker.utf8Length(it) }.assert().containsExactly(1, 2, 3, 4, 3, 4)
    }

    @Test
    fun `attribute paths and time fields are bounded like literals`() {
        val name = "a".repeat(1024 - "subject.attributes.".length)
        checked(Condition.Exists("subject.attributes.$name"))
        single(
            Condition.Exists("subject.attributes.${name}a")
        ).assert().isEqualTo(ConditionError("$.exists", "is 1025 bytes long, more than 1024"))
        single(Condition.Eq("subject.id", Operand.Attr("http.header.${"x".repeat(2000)}"))).assert()
            .isEqualTo(ConditionError("$.eq[1].attr", "is 2012 bytes long, more than 1024"))
        single(Condition.Exists("x".repeat(1025))).message.assert().isEqualTo("is 1025 bytes long, more than 1024")

        val long = "1".repeat(1025)
        listOf(
            Condition.Time(zone = long, daysOfWeek = listOf("MON")) to "$.time.zone",
            Condition.Time(notBefore = long) to "$.time.notBefore",
            Condition.Time(notAfter = long) to "$.time.notAfter",
            Condition.Time(from = long, to = "18:00") to "$.time.from",
            Condition.Time(from = "09:00", to = long) to "$.time.to",
            Condition.Time(daysOfWeek = listOf("MON", long)) to "$.time.daysOfWeek[1]",
        ).forEach { (condition, path) ->
            single(condition).assert().isEqualTo(ConditionError(path, "is 1025 bytes long, more than 1024"))
        }
        checked(Condition.Time(daysOfWeek = List(1024) { "MON" }))
        single(Condition.Time(daysOfWeek = List(1025) { "MON" }))
            .assert().isEqualTo(ConditionError("$.time.daysOfWeek", "has 1025 values, more than 1024"))
        // The zone resolver never sees an oversized id.
        val resolver = ZoneResolver { error("resolved $it") }
        single(Condition.Time(zone = long, daysOfWeek = listOf("MON")), ConditionChecker(zones = resolver))
            .path.assert().isEqualTo("$.time.zone")
    }

    @Test
    fun `cost is bounded`() {
        val time = Condition.Time(daysOfWeek = listOf("MON"))
        checked(Condition.Or(List(31) { time })).cost.assert().isEqualTo(1 + 31 * 8)
        single(Condition.Or(List(32) { time })).assert().isEqualTo(ConditionError("$", "costs 257, more than 256"))
        val tight = ConditionChecker(ConditionLimits(maxCost = 3))
        checked(Condition.Not(Condition.Eq("subject.id", str("a"))), tight).cost.assert().isEqualTo(3)
        single(
            Condition.Not(Condition.In("subject.id", listOf(str("a")))),
            tight
        ).message.assert().isEqualTo("costs 5, more than 3")
    }
}
