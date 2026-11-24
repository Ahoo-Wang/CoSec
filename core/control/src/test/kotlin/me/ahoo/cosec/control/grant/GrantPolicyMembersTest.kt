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
import me.ahoo.cosec.control.grant.GrantFixtures.deny
import me.ahoo.cosec.control.grant.GrantFixtures.eq
import me.ahoo.cosec.control.grant.GrantFixtures.role
import me.ahoo.cosec.control.grant.GrantFixtures.snapshot
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.HttpPathPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.PolicyId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * CTL-50: the analysis skips a policy identical to its previous version ([GrantEquality.samePolicy]), so identity must
 * hold in every member the grant depends on. Each member of [GrantPolicy], [GrantStatement] and the patterns they hold
 * is either compared, with a change of it alone shown to be analysed, or listed here with why it need not be: a member
 * added to one of these classes fails [every member is compared or listed with a reason] until it is one or the other.
 */
class GrantPolicyMembersTest {
    private val analyzer = GrantAnalyzer()

    /** Each class the skip's equality reads, and each member of it: compared, or why it is not. */
    private val members: Map<Class<*>, Map<String, String>> = mapOf(
        GrantPolicy::class.java to mapOf(
            "id" to "the key the previous version is looked up by: equal by construction",
            "statements" to COMPARED,
            "condition" to COMPARED,
            "attachable" to COMPARED,
        ),
        GrantStatement::class.java to mapOf(
            "effect" to COMPARED,
            "actions" to COMPARED,
            "resources" to COMPARED,
            "condition" to COMPARED,
            "name" to COMPARED,
            "onMissing" to COMPARED,
        ),
        ActionPattern::class.java to mapOf("service" to COMPARED, "verb" to COMPARED),
        HttpPathPattern::class.java to mapOf(
            "pattern" to COMPARED,
            "caseSensitive" to COMPARED,
            "decodeAndParseSegments" to COMPARED,
            "elements" to "compiled from the pattern and the two flags",
            "variableNames" to "parsed from the pattern",
            "globUnits" to "compiled from the pattern and the two flags",
            "names" to "parsed from the pattern",
            "leadingRest" to "compiled from the pattern",
            "matchesEmptyPath" to "compiled from the pattern",
            "literals" to "compiled from the pattern and the two flags",
            "prefix" to "compiled from the pattern and the two flags",
        ),
        ResourcePattern.Http::class.java to mapOf(
            "path" to COMPARED,
            "loose" to "compiled from the path and the statement's effect (PIP-35), which is compared",
        ),
        ResourcePattern.Typed::class.java to mapOf(
            "text" to COMPARED,
            "type" to "parsed from the text",
            "segments" to "parsed from the text",
            "rest" to "parsed from the text",
            "variableNames" to "parsed from the text",
            "names" to "parsed from the text",
        ),
    )

    @Test
    fun `every member is compared or listed with a reason`() {
        for ((type, listed) in members) {
            val fields = type.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
            fields.filterNot { it in listed }.assert()
                .describedAs("members of ${type.simpleName} the skip's equality neither compares nor lists (CTL-50)")
                .isEmpty()
            listed.keys.filterNot { it in fields }.assert()
                .describedAs("members listed for ${type.simpleName} that it no longer has").isEmpty()
        }
    }

    private val published = GrantDocument(
        listOf(
            policy(
                allow("order:read", "order/{id}", eq("resource.var.id", "1")),
                allow("http:GET", "http:path:/orders/**"),
                deny("order:delete"),
            ),
        ),
        listOf(bind("readers", role("reader"), "p")),
    )

    private fun policy(vararg statements: GrantStatement, condition: me.ahoo.cosec.model.condition.Condition? = null) =
        GrantPolicy(PolicyId("p"), statements.toList(), condition)

    private fun fragments(changed: GrantPolicy): List<GrantFragment> =
        analyzer.fragments(
            snapshot(tenant = published),
            GrantChange(ACME, GrantDocument(listOf(changed), published.bindings))
        )

    private val base: GrantPolicy get() = published.policies.single()

    private fun withStatement(index: Int, statement: GrantStatement) =
        GrantPolicy(
            base.id,
            base.statements.mapIndexed { i, old -> if (i == index) statement else old },
            base.condition
        )

    @Test
    fun `the unchanged policy grants nothing`() {
        fragments(base).assert().isEmpty()
    }

    @Test
    fun `a statement's condition removed is analysed`() {
        val first = base.statements[0]
        fragments(
            withStatement(0, GrantStatement(first.effect, first.actions, first.resources, null))
        ).assert().isNotEmpty()
    }

    @Test
    fun `a path pattern made case insensitive is analysed`() {
        val second = base.statements[1]
        val insensitive = ResourcePattern.parse("http:path:/orders/**", caseSensitive = false)
        fragments(
            withStatement(1, GrantStatement(second.effect, second.actions, listOf(insensitive)))
        ).assert().isNotEmpty()
    }

    @Test
    fun `a path pattern matched on the raw path is analysed`() {
        val second = base.statements[1]
        val raw = ResourcePattern.parse("http:path:/orders/**", decodeAndParseSegments = false)
        fragments(withStatement(1, GrantStatement(second.effect, second.actions, listOf(raw)))).assert().isNotEmpty()
    }

    @Test
    fun `a DENY turned into an ALLOW is analysed`() {
        val third = base.statements[2]
        fragments(withStatement(2, GrantStatement(me.ahoo.cosec.model.Effect.ALLOW, third.actions, third.resources)))
            .assert().isNotEmpty()
    }

    @Test
    fun `a wider action or resource is analysed`() {
        val second = base.statements[1]
        fragments(
            withStatement(1, GrantStatement(second.effect, listOf(ActionPattern.parse("http:*")), second.resources))
        )
            .assert().isNotEmpty()
        fragments(withStatement(1, GrantStatement(second.effect, second.actions, listOf(ResourcePattern.AnyResource))))
            .assert().isNotEmpty()
    }

    @Test
    fun `a policy condition removed, or the policy made attachable, is analysed`() {
        val conditioned = GrantDocument(
            listOf(policy(*base.statements.toTypedArray(), condition = eq("scope.space", "team-a"))),
            published.bindings,
        )
        analyzer.fragments(
            snapshot(tenant = conditioned),
            GrantChange(ACME, GrantDocument(listOf(base), published.bindings))
        )
            .assert().isNotEmpty()
        fragments(GrantPolicy(base.id, base.statements, base.condition, attachable = true)).assert().isNotEmpty()
    }

    private companion object {
        const val COMPARED = "compared by GrantEquality.samePolicy or sameStatement"
    }
}
