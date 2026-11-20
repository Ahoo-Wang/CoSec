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
import me.ahoo.cosec.control.grant.GrantFixtures.ACME
import me.ahoo.cosec.model.condition.ZoneResolver
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * TEMPORARY (removed with the old path): the fragments a change grants are the same, in the same order, with the
 * candidate's bindings grouped by policy and looked up in a set as they were with every binding scanned per policy.
 */
class GrantFragmenterEquivalenceTest {
    private val checker = ConditionChecker(ConditionLimits.DEFAULT, ZoneResolver.FIXED)
    private val equality = GrantEquality(checker)
    private val fragmenter = GrantFragmenter(GrantNarrowing(checker, equality), equality)

    /** A condition by its structure: `both` makes a new And of the policy's own condition objects on every call. */
    private fun render(condition: Condition?): String = when (condition) {
        null -> "-"
        is Condition.And -> condition.conditions.joinToString(",", "and(", ")") { render(it) }
        else -> "#" + System.identityHashCode(condition)
    }

    private fun render(fragment: GrantFragment): String = with(fragment) {
        "${tenant.value}|${policy.value}|$statement|$action|$resource|${render(condition)}|$grantee|$scope|$resolved"
    }

    @Test
    fun `the fragments are the same as before the index, over generated changes`() {
        val random = Random(0x1720)
        val generator = GrantGenerator(random)
        var fragments = 0
        repeat(ITERATIONS) {
            val snapshot = generator.snapshot()
            val base = snapshot.tenant(ACME)
            val fresh = generator.document("p", policies = random.nextInt(1, 5), bindings = random.nextInt(0, 8))
            // Some of the base's bindings kept as they are (unchanged), some of its policies removed (RC1-9), and new
            // or changed entries of the same ids.
            val keptBindings = base.bindings.filter { random.nextBoolean() }
            val policies = (base.policies.filter { random.nextInt(3) != 0 } + fresh.policies).associateBy { it.id }.values
            val bindings = keptBindings + fresh.bindings.filter { new -> keptBindings.none { it.id == new.id } }
            val change = GrantChange(ACME, GrantDocument(policies.toList(), bindings.shuffled(random)))
            val before = fragmenter.fragmentsBefore(snapshot, change).map(::render)
            val after = fragmenter.fragments(snapshot, change).map(::render)
            after.assert().describedAs("change $it").isEqualTo(before)
            fragments += after.size
        }
        fragments.assert().isGreaterThan(ITERATIONS)
    }

    private companion object {
        const val ITERATIONS = 20_000
    }
}
