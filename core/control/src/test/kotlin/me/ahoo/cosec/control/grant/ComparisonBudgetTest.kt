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
import me.ahoo.cosec.control.grant.GrantFixtures.allow
import me.ahoo.cosec.control.grant.GrantFixtures.bind
import me.ahoo.cosec.control.grant.GrantFixtures.grantor
import me.ahoo.cosec.control.grant.GrantFixtures.policy
import me.ahoo.cosec.control.grant.GrantFixtures.role
import me.ahoo.cosec.control.grant.GrantFixtures.snapshot
import me.ahoo.cosec.model.SpaceId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * CTL-49: an analysis over its comparison budget never answers contained. What it did not compare needs an approval: a
 * fragment it reaches past the budget is [UnknownReason.TOO_LARGE], and a statement still being compared when the
 * budget runs out is granted. The budget is spent in the analysis's order, so the same change stops at the same place.
 */
class ComparisonBudgetTest {
    private val space = SpaceId.DEFAULT.value
    private val gina = grantor(roles = mapOf(space to listOf("admin")))

    /** Gina holds `order:*` on every order; the change grants readers three things she holds. */
    private val published = GrantDocument(
        listOf(policy("held", allow("order:*", "order/**"))),
        listOf(bind("admins", role("admin"), "held", space = space)),
    )
    private val granting = GrantDocument(
        published.policies + policy("grant", allow("order:read", "order/1"), allow("order:read", "order/2"), allow("order:read", "order/3")),
        published.bindings + bind("readers", role("reader"), "grant", space = space),
    )

    private fun verdicts(analyzer: GrantAnalyzer, candidate: GrantDocument, base: GrantDocument = published) =
        analyzer.analyze(gina, snapshot(tenant = base), GrantChange(ACME, candidate)).verdicts.map { it.second }

    @Test
    fun `within the budget the fragments are contained`() {
        verdicts(GrantAnalyzer(), granting).assert().allMatch { it is GrantVerdict.Contained }
    }

    @Test
    fun `past the budget a fragment is too large to analyze, never contained`() {
        val verdicts = verdicts(GrantAnalyzer(maxComparisons = 1), granting)
        verdicts.first().assert().isInstanceOf(GrantVerdict.Contained::class.java)
        verdicts.drop(1).assert().allMatch { it is GrantVerdict.Unknown && it.reason == UnknownReason.TOO_LARGE }
        verdicts(GrantAnalyzer(maxComparisons = 0), granting)
            .assert().allMatch { it is GrantVerdict.Unknown && it.reason == UnknownReason.TOO_LARGE }
    }

    @Test
    fun `a statement still being compared when the budget runs out is granted`() {
        // Each later statement narrows the first; the change narrows the last one further, which grants nothing.
        val wide = policy("orders", allow("order:read", "order/**"), allow("order:read", "order/a/**"), allow("order:read", "order/b/**"))
        val base = GrantDocument(published.policies + wide, published.bindings + bind("readers", role("reader"), "orders", space = space))
        val narrowed = GrantDocument(
            published.policies + policy("orders", allow("order:read", "order/**"), allow("order:read", "order/a/**"), allow("order:read", "order/b/1")),
            base.bindings,
        )
        GrantAnalyzer().fragments(snapshot(tenant = base), GrantChange(ACME, narrowed)).assert().isEmpty()
        val analysis = GrantAnalyzer(maxComparisons = 0).analyze(gina, snapshot(tenant = base), GrantChange(ACME, narrowed))
        analysis.verdicts.map { it.first.policy.value to it.first.statement }.assert().containsExactly("orders" to 2)
        analysis.verdicts.single().second.assert().isInstanceOf(GrantVerdict.Unknown::class.java)
    }

    @Test
    fun `the same change stops at the same comparison`() {
        val analyzer = GrantAnalyzer(maxComparisons = 2)
        val first = analyzer.analyze(gina, snapshot(tenant = published), GrantChange(ACME, granting))
        val again = analyzer.analyze(gina, snapshot(tenant = published), GrantChange(ACME, granting))
        again.verdicts.map { it.second::class }.assert().isEqualTo(first.verdicts.map { it.second::class })
        again.comparisons.assert().isEqualTo(first.comparisons)
    }
}
