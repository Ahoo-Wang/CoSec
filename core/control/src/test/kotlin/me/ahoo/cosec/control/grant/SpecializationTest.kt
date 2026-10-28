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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The folding of RFC 0007 §7.4.3 `specialize`, operator by operator. */
class SpecializationTest {
    private val constants: (Attribute) -> Constant? = {
        when (it.kind) {
            Kind.SUBJECT_ID -> Constant.Str("gina")
            Kind.SUBJECT_AUTHENTICATED -> Constant.Bool(true)
            Kind.SUBJECT_ROLES -> Constant.Strings(setOf("ops"))
            Kind.SUBJECT_ATTRIBUTE -> Constant.Str(if (it.name == "region") "eu" else null)
            else -> null
        }
    }
    private val specialization = Specialization(
        ConditionChecker(),
        constants,
        opaque = { it.kind == Kind.SCOPE_ROLES },
        renames = mapOf("id" to "orderId"),
    )

    private fun fold(condition: Condition): Folded = specialization.fold(condition)

    private fun str(value: String) = Literal.Str(value)

    private fun residual(condition: Condition): String {
        val folded = fold(condition)
        folded.assert().isInstanceOf(Folded.Residual::class.java)
        return specialization.check((folded as Folded.Residual).condition).toString()
    }

    @Test
    fun `atoms over constants evaluate`() {
        fold(Condition.Eq("subject.id", str("gina"))).assert().isEqualTo(Folded.True)
        fold(Condition.Eq("subject.id", str("hal"))).assert().isEqualTo(Folded.False)
        fold(Condition.In("subject.attributes.region", listOf(str("eu"), str("us")))).assert().isEqualTo(Folded.True)
        fold(Condition.Prefix("subject.id", str("gi"))).assert().isEqualTo(Folded.True)
        fold(Condition.Contains(str("ops"), "subject.roles")).assert().isEqualTo(Folded.True)
        fold(Condition.Exists("subject.attributes.team")).assert().isEqualTo(Folded.False)
        fold(Condition.Eq("subject.authenticated", Literal.Bool(true))).assert().isEqualTo(Folded.True)
        fold(Condition.Eq("subject.attributes.team", Operand.Attr("subject.id"))).assert().isEqualTo(Folded.False)
    }

    @Test
    fun `groups and negation fold`() {
        fold(Condition.Not(Condition.Eq("subject.id", str("gina")))).assert().isEqualTo(Folded.False)
        fold(Condition.Not(Condition.Eq("subject.id", str("hal")))).assert().isEqualTo(Folded.True)
        fold(Condition.And(listOf(Condition.Eq("subject.id", str("gina")), Condition.Eq("subject.id", str("hal")))))
            .assert().isEqualTo(Folded.False)
        fold(Condition.Or(listOf(Condition.Eq("subject.id", str("hal")), Condition.Eq("subject.id", str("gina")))))
            .assert().isEqualTo(Folded.True)
        fold(Condition.Or(listOf(Condition.Eq("subject.id", str("hal")), Condition.Eq("subject.id", str("ivy")))))
            .assert().isEqualTo(Folded.False)
        fold(Condition.And(listOf(Condition.Eq("subject.id", str("gina")), Condition.Eq("scope.space", str("a")))))
            .assert().isInstanceOf(Folded.Residual::class.java)
        residual(
            Condition.Or(
                listOf(Condition.Eq("scope.space", str("a")), Condition.Not(Condition.Eq("scope.app", str("b"))))
            )
        ).assert().contains("or")
    }

    @Test
    fun `an eq between a constant and another attribute becomes an eq with a literal`() {
        residual(Condition.Eq("resource.var.id", Operand.Attr("subject.id"))).assert()
            .isEqualTo("""{"eq":["resource.var.orderId","gina"]}""")
        residual(Condition.Eq("subject.id", Operand.Attr("resource.var.id"), ignoreCase = true)).assert()
            .isEqualTo("""{"eq":["resource.var.orderId","gina"],"ignoreCase":true}""")
        residual(Condition.Eq("environment.blocked", Operand.Attr("subject.authenticated"))).assert()
            .isEqualTo("""{"eq":["environment.blocked",true]}""")
        residual(Condition.Eq("scope.app", Operand.Attr("scope.space"))).assert().contains("attr")
    }

    @Test
    fun `variables are renamed in every atom`() {
        residual(Condition.In("resource.var.id", listOf(str("1")))).assert().contains("resource.var.orderId")
        residual(Condition.Prefix("resource.var.id", str("1"))).assert().contains("resource.var.orderId")
        residual(Condition.Exists("resource.var.id")).assert().contains("resource.var.orderId")
        residual(Condition.Cidr("environment.ip", listOf(str("10.0.0.0/8")))).assert().contains("cidr")
        residual(Condition.Time(notAfter = "2030-01-01T00:00:00Z")).assert().contains("time")
    }

    @Test
    fun `what cannot be compared says so`() {
        fold(Condition.Eq("resource.var.other", str("1"))).assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.Contains(str("ops"), "scope.roles")).assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.Exists("subject.roles")).assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.Eq("ext.risk", str("low"))).assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.Eq("scope.app", Operand.Attr("ext.risk"))).assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.Eq("ext.risk", Operand.Attr("scope.app"))).assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.Not(Condition.Eq("ext.risk", str("low")))).assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.And(listOf(Condition.Eq("scope.app", str("a")), Condition.Eq("ext.risk", str("low")))))
            .assert().isInstanceOf(Folded.Incomparable::class.java)
        fold(Condition.Eq("scope.app", Operand.Attr("subject.roles"))).assert()
            .isInstanceOf(Folded.Incomparable::class.java)
        // A literal of the wrong type does not check, so it cannot be evaluated.
        fold(Condition.Eq("subject.id", Literal.Bool(true))).assert().isInstanceOf(Folded.Incomparable::class.java)
    }
}
