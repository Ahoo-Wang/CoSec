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
import me.ahoo.cosec.control.grant.GrantFixtures.eqAttr
import me.ahoo.cosec.control.grant.GrantFixtures.grantor
import me.ahoo.cosec.control.grant.GrantFixtures.policy
import me.ahoo.cosec.control.grant.GrantFixtures.role
import me.ahoo.cosec.control.grant.GrantFixtures.snapshot
import me.ahoo.cosec.model.SpaceId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * GRT-8 (R2C-31): the analysis renames the resource variables of a condition all at once, so a renaming never makes
 * two different variables one. Gina holds `res/{a}/{b}` only where `a == b`: a grant of `res/{b}/{c}` written with the
 * tautology `c == c` reaches `res/x/y`, which she does not hold, and a statement narrowed to it widens.
 */
class VariableRenamingTest {
    private val analyzer = GrantAnalyzer()
    private val space = SpaceId.DEFAULT.value
    private val gina = grantor(roles = mapOf(space to listOf("admin")))
    private val diagonal = allow("res:read", "res/{a}/{b}", eqAttr("resource.var.a", "resource.var.b"))
    private val published = GrantDocument(
        listOf(policy("held", diagonal)),
        listOf(bind("admins", role("admin"), "held", space = space)),
    )

    private fun verdicts(granted: GrantStatement): List<GrantVerdict> {
        val candidate = GrantDocument(
            published.policies + policy("grant", granted),
            published.bindings + bind("readers", role("reader"), "grant", space = space),
        )
        return analyzer.analyze(
            gina,
            snapshot(tenant = published),
            GrantChange(ACME, candidate)
        ).verdicts.map { it.second }
    }

    @Test
    fun `a shifted renaming does not contain a grant of every pair`() {
        verdicts(allow("res:read", "res/{b}/{c}", eqAttr("resource.var.c", "resource.var.c")))
            .assert().allMatch { it is GrantVerdict.Unknown }
    }

    @Test
    fun `a swapped renaming does not contain a grant of every pair`() {
        verdicts(allow("res:read", "res/{b}/{a}", eqAttr("resource.var.a", "resource.var.a")))
            .assert().allMatch { it is GrantVerdict.Unknown }
    }

    @Test
    fun `the same constraint written with other names is contained`() {
        verdicts(allow("res:read", "res/{b}/{c}", eqAttr("resource.var.b", "resource.var.c")))
            .assert().allMatch { it is GrantVerdict.Contained }
    }

    @Test
    fun `a statement rewritten to the shifted tautology widens, and is analysed`() {
        val before = GrantDocument(
            listOf(policy("p", diagonal)),
            listOf(bind("readers", role("reader"), "p", space = space))
        )
        val shifted = allow("res:read", "res/{b}/{c}", eqAttr("resource.var.c", "resource.var.c"))
        val after = GrantDocument(listOf(policy("p", shifted)), before.bindings)
        analyzer.fragments(snapshot(tenant = before), GrantChange(ACME, after)).assert().isNotEmpty()
        // The same constraint under other names still narrows: it grants nothing.
        val renamed = GrantDocument(
            listOf(policy("p", allow("res:read", "res/{b}/{c}", eqAttr("resource.var.b", "resource.var.c")))),
            before.bindings,
        )
        analyzer.fragments(snapshot(tenant = before), GrantChange(ACME, renamed)).assert().isEmpty()
    }
}
