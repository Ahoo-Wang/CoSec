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

class ConditionContainmentTest {
    private fun eq(attribute: String, value: String, ignoreCase: Boolean = false) =
        Condition.Eq(attribute, str(value), ignoreCase)

    private fun inValues(attribute: String, vararg values: String) = Condition.In(attribute, values.map(::str))

    private fun prefix(attribute: String, value: String, ignoreCase: Boolean = false) =
        Condition.Prefix(attribute, str(value), ignoreCase)

    private fun cidr(vararg blocks: String) = Condition.Cidr("environment.ip", blocks.map(::str))

    private fun and(vararg conditions: Condition) = Condition.And(conditions.toList())

    private fun or(vararg conditions: Condition) = Condition.Or(conditions.toList())

    private fun not(condition: Condition) = Condition.Not(condition)

    private fun exists(attribute: String) = Condition.Exists(attribute)

    private fun implies(c1: Condition, c2: Condition): Containment =
        ConditionContainment.implies(checked(c1), checked(c2))

    private fun yes(c1: Condition, c2: Condition) = implies(c1, c2).assert().isEqualTo(Containment.YES)

    private fun unknown(c1: Condition, c2: Condition) = implies(c1, c2).assert().isEqualTo(Containment.UNKNOWN)

    @Test
    fun `identical conditions after normalizing`() {
        val c = and(eq("subject.id", "a"), or(exists("scope.roles"), not(not(prefix("scope.space", "t")))))
        yes(c, c)
        yes(c, and(or(prefix("scope.space", "t"), exists("scope.roles")), eq("subject.id", "a")))
        yes(not(eq("subject.id", "a")), not(eq("subject.id", "a")))
    }

    @Test
    fun `the granter's condition and an extra one`() {
        val granter = and(eq("scope.space", "team-a"), cidr("10.0.0.0/8"))
        yes(and(granter, exists("http.header.x-a")), granter)
        yes(and(exists("http.header.x-a"), eq("scope.space", "team-a"), cidr("10.0.0.0/8")), granter)
        unknown(granter, and(granter, exists("http.header.x-a")))
        // A narrower conjunct is enough.
        yes(and(eq("scope.space", "team-a"), cidr("10.1.0.0/16")), granter)
    }

    @Test
    fun `disjunctions`() {
        yes(eq("subject.id", "a"), or(eq("subject.id", "a"), exists("scope.roles")))
        yes(or(eq("subject.id", "a"), eq("subject.id", "b")), inValues("subject.id", "a", "b", "c"))
        unknown(or(eq("subject.id", "a"), eq("subject.id", "z")), inValues("subject.id", "a", "b"))
        // c1 = and, c2 = or, no conjunct implies c2 on its own.
        yes(
            and(exists("scope.roles"), eq("subject.id", "a")),
            or(prefix("scope.space", "x"), and(exists("scope.roles"), prefix("subject.id", "a"))),
        )
        // Distributing is beyond the rules: sound to answer UNKNOWN.
        unknown(
            and(or(eq("subject.id", "a"), eq("subject.id", "b")), exists("scope.roles")),
            or(and(eq("subject.id", "a"), exists("scope.roles")), and(eq("subject.id", "b"), exists("scope.roles"))),
        )
    }

    @Test
    fun `eq and in`() {
        yes(eq("subject.id", "a"), inValues("subject.id", "a", "b"))
        unknown(eq("subject.id", "a"), inValues("subject.id", "b"))
        unknown(eq("subject.id", "a"), inValues("scope.space", "a"))
        yes(inValues("subject.id", "a", "b"), inValues("subject.id", "b", "a", "c"))
        unknown(inValues("subject.id", "a", "b"), inValues("subject.id", "a"))
        yes(inValues("subject.id", "a"), eq("subject.id", "a"))
        yes(inValues("subject.id", "Ab", "aB"), eq("subject.id", "ab", ignoreCase = true))
        yes(eq("subject.id", "Ab"), eq("subject.id", "aB", ignoreCase = true))
        yes(eq("subject.id", "Ab", ignoreCase = true), eq("subject.id", "aB", ignoreCase = true))
        // `ignoreCase` admits every case variant; a case-sensitive condition admits one.
        unknown(eq("subject.id", "ab", ignoreCase = true), eq("subject.id", "ab"))
        unknown(eq("subject.id", "ab", ignoreCase = true), inValues("subject.id", "ab", "AB"))
        // A finite set implies any condition on the same attribute that holds for each value.
        yes(eq("subject.id", "a"), not(eq("subject.id", "b")))
        unknown(eq("subject.id", "a"), not(eq("subject.id", "a")))
        yes(inValues("subject.id", "ab", "ac"), and(prefix("subject.id", "a"), not(eq("subject.id", "b"))))
        unknown(eq("subject.id", "a"), not(or(eq("subject.id", "a"), eq("scope.space", "b"))))
    }

    @Test
    fun `prefix`() {
        yes(eq("subject.id", "abc"), prefix("subject.id", "ab"))
        yes(eq("subject.id", "ABC"), prefix("subject.id", "ab", ignoreCase = true))
        unknown(eq("subject.id", "ABC"), prefix("subject.id", "ab"))
        yes(eq("subject.id", "abc", ignoreCase = true), prefix("subject.id", "AB", ignoreCase = true))
        unknown(eq("subject.id", "abc", ignoreCase = true), prefix("subject.id", "ab"))
        yes(prefix("subject.id", "abc"), prefix("subject.id", "ab"))
        unknown(prefix("subject.id", "ab"), prefix("subject.id", "abc"))
        yes(prefix("subject.id", "ABc"), prefix("subject.id", "ab", ignoreCase = true))
        yes(prefix("subject.id", "ABc", ignoreCase = true), prefix("subject.id", "ab", ignoreCase = true))
        unknown(prefix("subject.id", "abc", ignoreCase = true), prefix("subject.id", "ab"))
        unknown(prefix("subject.id", "abc"), prefix("scope.space", "ab"))
        yes(inValues("subject.id", "abc", "abd"), prefix("subject.id", "ab"))
        unknown(inValues("subject.id", "abc", "b"), prefix("subject.id", "ab"))
        unknown(prefix("subject.id", "ab"), eq("subject.id", "ab"))
    }

    @Test
    fun `labels`() {
        val labels = "http.origin.host.labels"
        yes(prefix(labels, "com.example.api"), prefix(labels, "com.example"))
        yes(prefix(labels, "COM.Example"), prefix(labels, "com.example"))
        unknown(prefix(labels, "com.examples"), prefix(labels, "com.example"))
        unknown(prefix(labels, "com"), prefix(labels, "com.example"))
        unknown(prefix(labels, "com.example"), prefix("http.referer.host.labels", "com.example"))
        // A host value decides its labels.
        yes(eq("http.origin.host", "api.example.com"), prefix(labels, "com.example"))
        yes(eq("http.origin.host", "example.com"), prefix(labels, "com.example"))
        unknown(eq("http.origin.host", "badexample.com"), prefix(labels, "com.example"))
        yes(prefix(labels, "com.example"), exists("http.origin.host"))
        yes(prefix(labels, "com.example"), exists(labels))
        yes(exists("http.origin.host"), exists(labels))
        yes(exists(labels), exists("http.origin.host"))
    }

    @Test
    fun `cidr and addresses`() {
        yes(cidr("10.1.0.0/16"), cidr("10.0.0.0/8"))
        yes(cidr("10.1.0.0/16", "10.2.3.4"), cidr("10.0.0.0/8", "192.168.0.0/16"))
        unknown(cidr("10.0.0.0/8"), cidr("10.1.0.0/16"))
        unknown(cidr("10.1.0.0/16", "11.0.0.0/8"), cidr("10.0.0.0/8"))
        // Covered by two blocks together, not by one: incomplete, but sound.
        unknown(cidr("10.0.0.0/8"), cidr("10.0.0.0/9", "10.128.0.0/9"))
        yes(cidr("2001:db8:1::/48"), cidr("2001:db8::/32"))
        unknown(cidr("2001:db8::/32"), cidr("0.0.0.0/0"))
        unknown(cidr("10.0.0.0/8"), cidr("::/0"))
        yes(cidr("::ffff:10.1.0.0/112"), cidr("10.0.0.0/8"))
        yes(eq("environment.ip", "10.1.2.3"), cidr("10.0.0.0/8"))
        yes(inValues("environment.ip", "10.1.2.3", "10.1.2.4"), cidr("10.1.2.0/24"))
        yes(cidr("10.1.2.3"), eq("environment.ip", "10.1.2.3"))
        unknown(cidr("10.1.2.0/31"), eq("environment.ip", "10.1.2.0"))
        unknown(eq("environment.ip", "11.1.2.3"), cidr("10.0.0.0/8"))
    }

    @Test
    fun `bool`() {
        val authenticated = "subject.authenticated"
        val isTrue = Condition.Eq(authenticated, Literal.Bool(true))
        val isFalse = Condition.Eq(authenticated, Literal.Bool(false))
        yes(isTrue, Condition.In(authenticated, listOf(Literal.Bool(true), Literal.Bool(false))))
        yes(isTrue, exists(authenticated))
        yes(isTrue, not(isFalse))
        unknown(isTrue, isFalse)
        // `exists` of a Bool is true or false.
        yes(exists(authenticated), or(isTrue, isFalse))
        unknown(exists(authenticated), isTrue)
    }

    @Test
    fun `exists`() {
        yes(eq("subject.id", "a", ignoreCase = true), exists("subject.id"))
        yes(prefix("subject.id", "a"), exists("subject.id"))
        yes(cidr("10.0.0.0/8"), exists("environment.ip"))
        yes(Condition.Contains(str("admin"), "scope.roles"), exists("scope.roles"))
        yes(Condition.Time(notBefore = "2026-01-01T00:00:00Z"), exists("environment.time"))
        yes(Condition.Eq("subject.id", Operand.Attr("scope.space")), exists("scope.space"))
        yes(Condition.Eq("subject.id", Operand.Attr("scope.space")), exists("subject.id"))
        unknown(prefix("subject.id", "a"), exists("scope.space"))
        // Absent is false, so its negation says nothing about presence.
        unknown(not(eq("subject.id", "a")), exists("subject.id"))
        unknown(Condition.Contains(str("admin"), "scope.roles"), exists("subject.roles"))
    }

    @Test
    fun `negation by contraposition`() {
        yes(not(exists("subject.id")), not(eq("subject.id", "a")))
        yes(not(cidr("10.0.0.0/8")), not(cidr("10.1.0.0/16")))
        unknown(not(cidr("10.1.0.0/16")), not(cidr("10.0.0.0/8")))
        unknown(not(eq("subject.id", "a")), eq("subject.id", "b"))
        unknown(not(not(not(eq("subject.id", "b")))), exists("subject.id"))
        unknown(not(exists("subject.id")), eq("scope.space", "a"))
    }

    @Test
    fun `attribute comparisons`() {
        val exact = Condition.Eq("subject.id", Operand.Attr("scope.space"))
        val folded = Condition.Eq("subject.id", Operand.Attr("scope.space"), ignoreCase = true)
        yes(exact, Condition.Eq("scope.space", Operand.Attr("subject.id")))
        yes(exact, folded)
        yes(folded, Condition.Eq("scope.space", Operand.Attr("subject.id"), ignoreCase = true))
        unknown(folded, exact)
        unknown(exact, Condition.Eq("subject.id", Operand.Attr("resource.id")))
        unknown(exact, eq("subject.id", "a"))
    }

    @Test
    fun `time periods`() {
        fun period(notBefore: String?, notAfter: String?, days: List<String>? = null) =
            Condition.Time(notBefore = notBefore, notAfter = notAfter, zone = "UTC", daysOfWeek = days)
        val year = period("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")
        yes(period("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z"), year)
        yes(period("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", listOf("MON")), year)
        yes(period("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z"), period(null, "2027-01-01T00:00:00Z"))
        yes(period("2026-03-01T00:00:00Z", null), period("2025-01-01T00:00:00Z", null))
        unknown(period(null, "2026-04-01T00:00:00Z"), year)
        unknown(period("2026-03-01T00:00:00Z", null), year)
        unknown(period("2025-12-31T23:59:59Z", "2026-04-01T00:00:00Z"), year)
        unknown(period("2026-03-01T00:00:00Z", "2027-01-01T00:00:00.001Z"), year)
        // Sub-millisecond bounds compare as evaluation rounds them.
        yes(period("2026-01-01T00:00:00.0000001Z", null), period("2026-01-01T00:00:00.001Z", null))
        // A window or days on the right: identical only.
        val mondays = period("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", listOf("MON"))
        yes(mondays, mondays)
        unknown(period("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z", listOf("MON")), mondays)
        unknown(year, mondays)
        // The same configuration in another zone is another condition.
        unknown(mondays, Condition.Time("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "+08:00", listOf("MON")))
    }

    @Test
    fun `identical renderings with different zone tables are not identical`() {
        val condition = Condition.Time(zone = "Europe/London", daysOfWeek = listOf("MON"))
        val real = checked(condition)
        val fake = checked(condition, ConditionChecker(zones = { ZoneOffsets(it, LongArray(0), IntArray(1) { 3600 }) }))
        real.assert().isEqualTo(fake)
        ConditionContainment.implies(real, fake).assert().isEqualTo(Containment.UNKNOWN)
        ConditionContainment.implies(and(condition, exists("subject.id")).let(::checked), fake)
            .assert().isEqualTo(Containment.UNKNOWN)
        ConditionContainment.implies(real, checked(condition)).assert().isEqualTo(Containment.YES)
    }

    @Test
    fun `containment of the largest conditions stays fast`() {
        val wide = Condition.And(List(7) { i -> Condition.Or(List(7) { j -> eq("subject.id", "v$i$j") }) })
        val other = Condition.Or(List(7) { i -> Condition.And(List(7) { j -> prefix("scope.space", "p$i$j") }) })
        checked(wide).nodes.assert().isLessThanOrEqualTo(ConditionLimits.DEFAULT.maxNodes)
        implies(wide, other).assert().isEqualTo(Containment.UNKNOWN)
        implies(other, wide).assert().isEqualTo(Containment.UNKNOWN)
    }
}
