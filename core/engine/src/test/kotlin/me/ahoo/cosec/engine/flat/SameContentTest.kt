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
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
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

    /**
     * Same content is a comparison of what was written, so two copies compiled against different platform inputs can
     * pass for the same and still decide differently: here `ext.p.flag` is a Bool for the platform's copy and a String
     * for the tenant's, which an older platform revision declared. The engine evaluates the platform's copy, so the
     * comparison only decides whether the id resolves, never which rules apply.
     */
    @Test
    fun `when both layers define the same content, the platform's copy decides`() {
        val path = "ext.p.flag"
        fun copy(type: AttributeType) = PolicyCompiler(
            ConditionChecker(catalog = AttributeCatalog.of(listOf(Attribute.external(path, type))))
        ).compile(
            PolicyId("p"),
            null,
            listOf(StatementSource("s", Effect.DENY, listOf("*"), condition = Condition.Exists(path))),
            attachable = true,
        )
        val shared = copy(AttributeType.BOOL)
        val own = copy(AttributeType.STRING)
        own.sameContent(shared).assert().isTrue()
        val allow = compiler.compile(PolicyId("allow"), null, listOf(StatementSource("a", Effect.ALLOW, listOf("*"))))
        val platform = CompiledDocument.platform(listOf(shared, allow), listOf(anyone("b", "allow")))
        val external = ExternalValues(mapOf(path to true), setOf(path))
        fun decide(tenant: CompiledDocument, attachments: List<String>): Decision {
            val base = flatRequest(attachments = attachments)
            val request = FlatRequest(base.request, base.scope, base.memberships, base.time, base.attributes, external)
            val view = SnapshotView(platform, null, tenant)
            return TraceLevel.entries.map { CoSecFlatEngine.evaluate(request, view, it).decision }.distinct().single()
        }
        val attached = decide(tenant(own), listOf("p"))
        (attached as Decision.Deny).by.assert().isEqualTo(
            ref("p", 0, BindingRef.Attached(PolicyId("p"))).copy(name = "s")
        )
        val bound = decide(tenant(own, bindings = listOf(anyone("t", "p"))), emptyList())
        (bound as Decision.Deny).by.assert()
            .isEqualTo(ref("p", 0, bound("t", DocumentKind.TENANT)).copy(name = "s"))
    }
}
