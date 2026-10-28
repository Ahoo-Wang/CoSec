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
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class IndexKeysTest {
    private val space = attr("scope.space")

    private fun keys(condition: Condition): IndexKeys = IndexKeys.of(checked(condition), space)

    private fun only(folded: Boolean, vararg values: String) = IndexKeys.Only(values.toSet(), folded)

    private fun eq(value: String, ignoreCase: Boolean = false) = Condition.Eq(space.path, str(value), ignoreCase)

    private fun inValues(vararg values: String) = Condition.In(space.path, values.map(::str))

    @Test
    fun `eq and in constrain`() {
        keys(eq("a")).assert().isEqualTo(only(false, "a"))
        keys(eq("A", ignoreCase = true)).assert().isEqualTo(only(true, "a"))
        keys(inValues("a", "b")).assert().isEqualTo(only(false, "a", "b"))
    }

    @Test
    fun `other operators and attributes are unconstrained`() {
        keys(Condition.Eq("subject.id", str("a"))).assert().isSameAs(IndexKeys.Unconstrained)
        keys(Condition.In("subject.id", listOf(str("a")))).assert().isSameAs(IndexKeys.Unconstrained)
        keys(Condition.Prefix(space.path, str("a"))).assert().isSameAs(IndexKeys.Unconstrained)
        keys(Condition.Exists(space.path)).assert().isSameAs(IndexKeys.Unconstrained)
        keys(Condition.Eq(space.path, Operand.Attr("subject.id"))).assert().isSameAs(IndexKeys.Unconstrained)
        keys(Condition.Not(Condition.Not(eq("a")))).assert().isSameAs(IndexKeys.Unconstrained)
        keys(Condition.Not(eq("a"))).assert().isSameAs(IndexKeys.Unconstrained)
    }

    @Test
    fun `and intersects`() {
        keys(Condition.And(listOf(inValues("a", "b", "c"), inValues("b", "c", "d"), Condition.Exists("subject.id"))))
            .assert().isEqualTo(only(false, "b", "c"))
        keys(Condition.And(listOf(Condition.Exists("subject.id")))).assert().isSameAs(IndexKeys.Unconstrained)
        // Unsatisfiable: no bucket.
        keys(Condition.And(listOf(eq("a"), eq("b")))).assert().isEqualTo(only(false))
        // Exact constraints stay exact, filtered by the folded ones.
        keys(Condition.And(listOf(inValues("Ab", "b", "c"), eq("AB", ignoreCase = true))))
            .assert().isEqualTo(only(false, "Ab"))
        keys(
            Condition.And(
                listOf(eq("Ab", ignoreCase = true), inValues("ab", "x", "y").let { Condition.Or(listOf(it)) })
            )
        )
            .assert().isEqualTo(only(false, "ab"))
        keys(Condition.And(listOf(eq("Ab", ignoreCase = true), eq("aB", ignoreCase = true))))
            .assert().isEqualTo(only(true, "ab"))
    }

    @Test
    fun `or unites`() {
        keys(Condition.Or(listOf(eq("a"), inValues("b", "c")))).assert().isEqualTo(only(false, "a", "b", "c"))
        keys(Condition.Or(listOf(eq("a"), Condition.Exists("subject.id")))).assert().isSameAs(IndexKeys.Unconstrained)
        // Mixing folds.
        keys(Condition.Or(listOf(eq("A"), eq("B", ignoreCase = true)))).assert().isEqualTo(only(true, "a", "b"))
    }

    @Test
    fun `admits folds when the keys are folded`() {
        only(true, "ab").admits("AB").assert().isTrue()
        only(true, "ab").admits("ac").assert().isFalse()
        only(false, "ab").admits("AB").assert().isFalse()
        only(false, "ab").admits("ab").assert().isTrue()
        only(false, "ab").admits(null).assert().isFalse()
        IndexKeys.Unconstrained.admits(null).assert().isTrue()
        only(true, "b", "a").toString().assert().isEqualTo("folded[a, b]")
        IndexKeys.Unconstrained.toString().assert().isEqualTo("⊤")
        only(false, "a").hashCode().assert().isEqualTo(only(false, "a").hashCode())
        only(false, "a").assert().isNotEqualTo(only(true, "a"))
    }

    @Test
    fun `only String attributes have keys`() {
        assertThrownBy<IllegalArgumentException> {
            IndexKeys.of(checked(eq("a")), attr("environment.ip"))
        }
    }
}
