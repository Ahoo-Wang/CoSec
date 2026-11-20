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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * CTL-50: a statement written as one of the previous version's is unchanged, whatever its condition. The analysis
 * skips an identical policy and looks an identical statement up by its twin on that ground, so it must hold for every
 * shape: twins are built apart, as a candidate and its previous version are decoded apart, and conditions that do not
 * check (an attribute outside the catalog) are among them.
 */
class GrantReflexivityTest {
    private val iterations = maxOf(3_000, Integer.getInteger("cosec.property.iterations", 0))
    private val checker = ConditionChecker()
    private val equality = GrantEquality(checker)
    private val narrowing = GrantNarrowing(checker, equality)

    /** The same statement twice, built from the same draws: equal, never the same instance. */
    private fun twins(seed: Long): Pair<GrantPolicy, GrantPolicy> {
        fun build(): GrantPolicy {
            val random = Random(seed)
            val generator = GrantGenerator(random)
            val statements = List(random.nextInt(1, 4)) {
                val statement = generator.statement()
                if (random.nextInt(4) == 0) {
                    // An attribute no catalog declares: the condition does not check.
                    GrantStatement(
                        statement.effect,
                        statement.actions,
                        statement.resources,
                        Condition.And(listOfNotNull(statement.condition, Condition.Eq("ext.unlisted", Literal.Str("x")))),
                    )
                } else {
                    statement
                }
            }
            return GrantPolicy(PolicyId("p"), statements, generator.condition())
        }
        return build() to build()
    }

    @Test
    fun `a twin policy is the same policy, and each twin statement narrows its previous one`() {
        var unchecked = 0
        repeat(iterations) { i ->
            val (previous, policy) = twins(i.toLong())
            equality.samePolicy(previous, policy).assert().describedAs("seed $i").isTrue()
            for (index in policy.statements.indices) {
                val statement = policy.statements[index]
                if (statement.condition?.let(equality::check) == null && statement.condition != null) unchecked++
                equality.sameStatement(previous.statements[index], statement).assert().describedAs("seed $i").isTrue()
                narrowing.narrowsAny(policy, statement, previous).assert()
                    .describedAs("seed $i: statement $index does not narrow its twin").isTrue()
            }
        }
        unchecked.assert().isGreaterThan(iterations / 10)
    }

    /**
     * `narrows` alone, without the identity shortcut. A statement whose (combined) condition checks and reads no
     * resource variable narrows its twin. Two shapes do not, by design, and only identity makes them unchanged, which
     * is why the shortcut compares written conditions: a condition that does not check narrows nothing (`narrows`
     * fails closed on it), and implication under a containment's variable renames is not shown for a condition that
     * reads `resource.var.*`, whether or not the resource captures it (measured 2026-11-20: about a fifth of the
     * generated statements whose condition checks).
     */
    @Test
    fun `narrows is reflexive for every condition that checks and reads no resource variable`() {
        var checked = 0
        repeat(iterations) { i ->
            val (previous, policy) = twins(i.toLong())
            for (index in policy.statements.indices) {
                val statement = policy.statements[index]
                val condition = both(policy.condition, statement.condition)
                val previousCondition = both(previous.condition, previous.statements[index].condition)
                val narrows = narrowing.narrows(statement, condition, previous.statements[index], previousCondition)
                when {
                    condition != null && equality.check(condition) == null ->
                        narrows.assert().describedAs("seed $i: a condition that does not check narrows nothing").isFalse()
                    readsResourceVariable(condition) -> Unit
                    else -> {
                        checked++
                        narrows.assert().describedAs("seed $i: statement $index does not narrow its twin").isTrue()
                    }
                }
            }
        }
        checked.assert().isGreaterThan(iterations / 2)
    }

    private fun readsResourceVariable(condition: Condition?): Boolean = when (condition) {
        null -> false
        is Condition.And -> condition.conditions.any(::readsResourceVariable)
        is Condition.Or -> condition.conditions.any(::readsResourceVariable)
        is Condition.Not -> readsResourceVariable(condition.condition)
        is Condition.Eq -> condition.attribute.startsWith(VARIABLE) ||
            (condition.value as? Operand.Attr)?.path?.startsWith(VARIABLE) == true
        is Condition.In -> condition.attribute.startsWith(VARIABLE)
        is Condition.Contains -> condition.attribute.startsWith(VARIABLE)
        is Condition.Prefix -> condition.attribute.startsWith(VARIABLE)
        is Condition.Cidr -> condition.attribute.startsWith(VARIABLE)
        is Condition.Exists -> condition.attribute.startsWith(VARIABLE)
        is Condition.Time -> false
    }

    private companion object {
        const val VARIABLE = "resource.var."
    }
}
