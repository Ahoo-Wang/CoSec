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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.model.condition

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `DOC-9` and `DOC-11`: what [ConditionLints] finds, and in which direction it refuses. */
class ConditionLintsTest {
    private val umlaut = Condition.Eq("subject.attributes.dept", str("Ärzte"), ignoreCase = true)

    /** As a DENY statement or a rate limit rule holds it. */
    private fun deny(condition: Condition) = ConditionLints.check(condition, fewerAdmits = true, moreAdmits = false)

    /** As an ALLOW statement holds it. */
    private fun allow(condition: Condition) = ConditionLints.check(condition, fewerAdmits = false, moreAdmits = true)

    @Test
    fun `ignoreCase on a letter beyond ASCII refuses where a miss lets a request through`() {
        val finding = deny(umlaut).single()
        finding.refuses.assert().isTrue()
        finding.path.assert().isEqualTo("$.eq[1]")
        finding.message.assert().contains("[Ärzte]").contains("[ärzte]").contains("list every spelling in an in")
            .contains("DOC-9")
        allow(Condition.Not(umlaut)).single().refuses.assert().isTrue()
        val prefix = Condition.Prefix("subject.attributes.dept", str("Öl-"), ignoreCase = true)
        deny(prefix).single().message.assert().contains("[öl-]").contains("an or of one prefix per spelling")
    }

    @Test
    fun `the direction flips under each not and is kept by and and or`() {
        // A DENY under one not: the miss makes the DENY hold more, a narrowing.
        deny(Condition.Not(umlaut)).single().refuses.assert().isFalse()
        deny(Condition.Not(Condition.Not(umlaut))).single().refuses.assert().isTrue()
        allow(umlaut).single().refuses.assert().isFalse()
        allow(umlaut).single().message.assert().contains("only narrows")
        val nested = Condition.And(listOf(Condition.Exists("subject.id"), Condition.Or(listOf(Condition.Not(umlaut)))))
        val finding = allow(nested).single()
        finding.refuses.assert().isTrue()
        finding.path.assert().isEqualTo("$.and[1].or[0].not.eq[1]")
        // A policy condition guarding both effects refuses in both directions.
        ConditionLints.check(umlaut, fewerAdmits = true, moreAdmits = true).single().refuses.assert().isTrue()
        ConditionLints.check(Condition.Not(umlaut), fewerAdmits = true, moreAdmits = true).single().refuses.assert().isTrue()
    }

    @Test
    fun `what the ASCII fold reaches is no finding`() {
        deny(Condition.Eq("subject.attributes.dept", str("Doctors"), ignoreCase = true)).assert().isEmpty()
        // Without ignoreCase the author compares exactly, whatever the letters.
        deny(Condition.Eq("subject.attributes.dept", str("Ärzte"))).assert().isEmpty()
        // A character beyond ASCII without case: nothing to fold.
        deny(Condition.Eq("subject.attributes.dept", str("医生-1"), ignoreCase = true)).assert().isEmpty()
        deny(Condition.Eq("subject.attributes.dept", Operand.Attr("subject.tenant"), ignoreCase = true)).assert().isEmpty()
    }

    @Test
    fun `case mappings beyond ASCII`() {
        ConditionLints.foldsBeyondAscii("Ärzte").assert().isTrue()
        ConditionLints.foldsBeyondAscii("Kelvin").assert().isTrue()
        ConditionLints.foldsBeyondAscii("𐐀").assert().isTrue() // Deseret capital long I, a surrogate pair
        ConditionLints.foldsBeyondAscii("Kelvin").assert().isFalse()
        ConditionLints.hasCaseMapping("Kelvin").assert().isTrue()
        ConditionLints.hasCaseMapping("2024-01").assert().isFalse()
        ConditionLints.otherSpelling("Ärzte").assert().isEqualTo("ärzte")
        ConditionLints.otherSpelling("über").assert().isEqualTo("Über")
        ConditionLints.otherSpelling("𐐀").assert().isEqualTo("𐐨")
    }

    @Test
    fun `a subject id literal beginning with a parenthesis refuses in every direction`() {
        val anonymous = Condition.Eq("subject.id", str("(0)"))
        for (findings in listOf(deny(anonymous), allow(anonymous), deny(Condition.Not(anonymous)), allow(Condition.Not(anonymous)))) {
            val finding = findings.single()
            finding.refuses.assert().isTrue()
            finding.path.assert().endsWith("eq[1]")
            finding.message.assert().contains("subject.authenticated").contains("DOC-11")
        }
        deny(Condition.In("subject.id", listOf(str("alice"), str("(0)")))).single().path.assert().isEqualTo("$.in[1][1]")
        deny(Condition.Prefix("subject.id", str("(p"))).single().path.assert().isEqualTo("$.prefix[1]")
    }

    @Test
    fun `other ids and other attributes may begin with a parenthesis`() {
        deny(Condition.Eq("subject.id", str("alice"))).assert().isEmpty()
        deny(Condition.Eq("subject.tenant", str("(0)"))).assert().isEmpty()
        deny(Condition.Eq("scope.tenant", str("(platform)"))).assert().isEmpty()
        deny(Condition.In("subject.attributes.id", listOf(str("(0)")))).assert().isEmpty()
        deny(Condition.Eq("subject.id", Operand.Attr("subject.attributes.owner"))).assert().isEmpty()
    }

    @Test
    fun `the resource attribute message says where facts about a resource go`() {
        ConditionLints.unsetResourceAttribute("resource.attributes.x").assert()
            .contains("`resource.attributes.x` is never set").contains("Object-level rules").contains("DOC-10")
    }
}
