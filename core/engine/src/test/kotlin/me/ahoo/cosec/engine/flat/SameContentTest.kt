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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `BND-7`: a tenant policy and a platform policy of one id resolve only when they mean the same. */
class SameContentTest {
    private val compiler = PolicyCompiler()

    private fun policy(
        vararg resources: ResourceSource,
        actions: List<String> = listOf("*"),
        name: String = "s",
        attachable: Boolean = true,
        condition: Condition? = null,
        policyCondition: Condition? = null,
    ) = compiler.compile(
        PolicyId("no-admin"),
        policyCondition,
        listOf(StatementSource(name, Effect.DENY, actions, resources.toList(), condition)),
        attachable = attachable,
    )

    private fun http(pattern: String, caseSensitive: Boolean = true, decode: Boolean = true) =
        ResourceSource("http:path:$pattern", caseSensitive, decode)

    @Test
    fun `two policies whose parts join into the same text are not the same`() {
        val shared = policy(http("/admin/x"), http("/internal/y"))
        val own = policy(http("/admin/x(true,true),http:path:/internal/y"))
        own.sameContent(shared).assert().isFalse()
        policy(ResourceSource("order/a"), ResourceSource("order/b"))
            .sameContent(policy(ResourceSource("order/a,order/b"))).assert().isFalse()
    }

    @Test
    fun `an attachment both layers define with joined-text twins is ambiguous`() {
        val allow = compiler.compile(PolicyId("allow"), null, listOf(StatementSource("a", Effect.ALLOW, listOf("*"))))
        val platform = CompiledDocument.platform(
            listOf(policy(http("/admin/x"), http("/internal/y")), allow),
            listOf(anyone("b", "allow"))
        )
        val view = SnapshotView(platform, null, tenant(policy(http("/admin/x(true,true),http:path:/internal/y"))))
        val request = flatRequest(path = "/admin/x", attachments = listOf("no-admin"))
        val decision = TraceLevel.entries.map {
            CoSecFlatEngine.evaluate(
                request,
                view,
                it
            ).decision
        }.distinct().single()
        ((decision as Decision.Indeterminate).cause as Cause.AmbiguousPolicy).policyIds.assert().containsExactly(
            PolicyId("no-admin")
        )
    }

    @Test
    fun `every part of a statement counts`() {
        val base = policy(http("/a"))
        base.sameContent(policy(http("/a"))).assert().isTrue()
        base.sameContent(policy(http("/a", caseSensitive = false))).assert().isFalse()
        base.sameContent(policy(http("/a", decode = false))).assert().isFalse()
        base.sameContent(policy(http("/a"), name = "t")).assert().isFalse()
        base.sameContent(policy(http("/a"), attachable = false)).assert().isFalse()
        base.sameContent(policy(http("/a"), actions = listOf("http:GET"))).assert().isFalse()
        base.sameContent(policy(http("/a"), condition = Condition.Eq("scope.app", Literal.Str("x")))).assert().isFalse()
        base.sameContent(
            policy(http("/a"), policyCondition = Condition.Eq("scope.app", Literal.Str("x")))
        ).assert().isFalse()
        policy(http("/a"), condition = Condition.Eq("scope.app", Literal.Str("x")))
            .sameContent(policy(http("/a"), condition = Condition.Eq("scope.app", Literal.Str("x")))).assert().isTrue()
    }
}
