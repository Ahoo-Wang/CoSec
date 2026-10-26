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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class CompiledDocumentTest {
    private val fixture = FlatFixture()
    private val p = fixture.policy("p", allow())

    private fun rejects(message: String, compile: () -> Any) {
        assertThrownBy<IllegalArgumentException> { compile() }.hasMessageContaining(message)
    }

    @Test
    fun `ids are unique within a document`() {
        rejects("Duplicate policy id p") { platform(p, fixture.policy("p", deny())) }
        rejects("Duplicate binding id g") { platform(p, bindings = listOf(anyone("g", "p"), principal("g", "p"))) }
    }

    @Test
    fun `builtin policies can be neither defined nor bound by hand`() {
        rejects("builtin policy cosec:root") { tenant(fixture.policy("cosec:root", allow())) }
        rejects("builtin policy cosec:blacklist") { platform(fixture.policy("cosec:blacklist", deny())) }
        rejects("binds the blacklist rule") { platform(bindings = listOf(anyone("g", "cosec:blacklist"))) }
        rejects("without being break-glass") { platform(bindings = listOf(principal("g", "cosec:root"))) }
        rejects("without being break-glass") { tenant(bindings = listOf(anyone("g", "cosec:root"))) }
    }

    @Test
    fun `break-glass bindings are expiring principal bindings of the platform`() {
        rejects("has no expiry") { platform(bindings = listOf(principal("r", "cosec:root", breakGlass = true))) }
        rejects("in the TENANT document") { tenant(bindings = listOf(root())) }
        val anyoneBreakGlass = anyone("r", "cosec:root", expiresAt = NOW)
        rejects("must bind a principal") {
            platform(
                bindings = listOf(
                    with(anyoneBreakGlass) { Binding(id, subject, scope, policy, NOW, breakGlass = true) }
                )
            )
        }
        platform(bindings = listOf(root())).kind.assert().isEqualTo(DocumentKind.PLATFORM)
    }

    @Test
    fun `each kind of document binds only the subjects the candidate order reads from it`() {
        rejects("Role binding g in the platform document") { platform(p, bindings = listOf(role("g", "editor", "p"))) }
        rejects("does not bind a role") { app(p, bindings = listOf(anyone("g", "p"))) }
        rejects("does not bind a role") { app(p, bindings = listOf(principal("g", "p"))) }
        tenant(
            p,
            bindings = listOf(anyone("a", "p"), principal("b", "p"), role("c", "editor", "p"))
        ).owner.assert().isEqualTo("acme")
    }

    @Test
    fun `a platform binding names a platform policy, others may name one they do not define`() {
        rejects("which it does not define") { platform(bindings = listOf(anyone("g", "elsewhere"))) }
        app(bindings = listOf(role("g", "editor", "elsewhere"))).owner.assert().isEqualTo("app")
        tenant(bindings = listOf(anyone("g", "elsewhere"))).kind.assert().isEqualTo(DocumentKind.TENANT)
    }

    @Test
    fun `a statement must have an effect`() {
        val broken = object : Rule {
            override val effect: Effect = javaNull()
            override val index = 3
            override val name = "broken"
            override val id: String? = null

            override fun match(request: AuthzRequest): RuleMatch = RuleMatch.MATCHED
        }
        rejects("Statement 3 of policy b has no effect") { CompiledPolicy(PolicyId("b"), null, listOf(broken)) }
    }

    @Test
    fun `a view composes documents of the right kinds`() {
        rejects("Not a platform document") { SnapshotView(tenant(), null, tenant()) }
        rejects("Not an app document") { SnapshotView(platform(), tenant(), tenant()) }
        rejects("Not a tenant document") { SnapshotView(platform(), null, app()) }
    }

    @Test
    fun `a compiled policy keeps its id and splits its statements by effect`() {
        val policy = fixture.policy("q", allow(), deny(), allow())
        policy.policyId.assert().isEqualTo(PolicyId("q"))
        policy.denies.map { it.index }.assert().containsExactly(1)
        policy.allows.map { it.index }.assert().containsExactly(0, 2)
        BuiltinPolicies.root.attachable.assert().isFalse()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> javaNull(): T = null as T
}
