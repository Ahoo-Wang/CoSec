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
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.RateLimitSpec
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
        rejects("has no expiry") {
            platform(bindings = listOf(principal("r", "cosec:root", tenant = "(platform)", breakGlass = true)))
        }
        // Root is for the platform's administrators: a tenant's principal of the same id is someone else.
        rejects("must bind a principal of the platform tenant") {
            platform(bindings = listOf(root(tenant = "acme", principal = "alice")))
        }
        rejects("must bind a principal of the platform tenant") {
            platform(p, bindings = listOf(principal("r", "p", tenant = "(0)", expiresAt = NOW, breakGlass = true)))
        }
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

    /** A binding no request can reach would silently drop a DENY. */
    @Test
    fun `a binding that can never apply is rejected`() {
        rejects("binds a subject of tenant beta") { tenant(p, bindings = listOf(principal("x", "p", tenant = "beta"))) }
        rejects("binds a subject of tenant beta") {
            tenant(p, bindings = listOf(role("x", "editor", "p", tenant = "beta")))
        }
        rejects("is scoped to another app") { app(p, bindings = listOf(role("x", "editor", "p", app = "other"))) }
        rejects("is scoped to another app") { app(p, bindings = listOf(role("x", "editor", "p", app = ""))) }
        // Their own tenant, any tenant, their own app or any app: fine.
        tenant(
            p,
            bindings = listOf(principal("a", "p"), role("b", "editor", "p", tenant = "acme"), role("c", "editor", "p")),
        ).owner.assert().isEqualTo("acme")
        app(p, bindings = listOf(role("a", "editor", "p"), role("b", "editor", "p", app = null))).owner.assert()
            .isEqualTo("app")
    }

    @Test
    fun `a platform binding names a platform policy, others may name one they do not define`() {
        rejects("which it does not define") { platform(bindings = listOf(anyone("g", "elsewhere"))) }
        app(bindings = listOf(role("g", "editor", "elsewhere"))).owner.assert().isEqualTo("app")
        tenant(bindings = listOf(anyone("g", "elsewhere"))).kind.assert().isEqualTo(DocumentKind.TENANT)
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

    /**
     * RC1-8 (`DOC-4`): the glob units of a document's path patterns, across its policies, boundaries and rate limit
     * rules, bound what matching them all costs one request, whichever are candidates. A rule whose key reads a
     * captured variable counts twice: it is matched, then captured.
     */
    @Test
    fun `a document holds at most its glob budget`() {
        val compiler = PolicyCompiler()
        fun globs(id: String, statements: Int): CompiledPolicy {
            val policyId = PolicyId(id)
            return CompiledPolicy(
                policyId,
                null,
                (0 until statements).map { k ->
                    compiler.statement(
                        policyId,
                        k,
                        StatementSource("s$k", Effect.DENY, listOf("*"), listOf(ResourceSource(GLOB)))
                    )
                },
            )
        }
        val max = CompiledDocument.MAX_GLOB_UNITS
        val full = max / GLOB_UNITS
        globs("a", 1).globUnits.assert().isEqualTo(GLOB_UNITS)
        tenant(globs("a", full / 2), globs("b", full - full / 2)).headersRead.assert().isEmpty()
        rejects(
            "hold ${max + GLOB_UNITS} tokens and literal characters in all, more than $max"
        ) { tenant(globs("a", full / 2), globs("b", full - full / 2 + 1)) }
        fun rule(key: String?) = compiler.rateLimit(
            DocumentKind.PLATFORM,
            "",
            RateLimitSource(
                "r",
                StatementSource("r", Effect.DENY, listOf("*"), listOf(ResourceSource(GLOB))),
                key,
                RateLimitSpec(1.0)
            ),
        )
        val rule = rule(null)
        CompiledDocument.platform(listOf(globs("a", full - 1)), emptyList(), listOf(rule))
        rejects("more than $max") { CompiledDocument.platform(listOf(globs("a", full)), emptyList(), listOf(rule)) }
        val keyed = rule("resource.var.v")
        CompiledDocument.platform(listOf(globs("a", full - 2)), emptyList(), listOf(keyed))
        rejects("more than $max") {
            CompiledDocument.platform(listOf(globs("a", full - 1)), emptyList(), listOf(keyed))
        }
        // A boundary's statements count as a policy's do.
        val boundary = globsAllow(compiler, CompiledDocument.MAX_BOUNDARY_STATEMENTS)
        val rest = full - CompiledDocument.MAX_BOUNDARY_STATEMENTS
        CompiledDocument.platform(listOf(globs("a", rest - 1)), emptyList(), listOf(rule), listOf(boundary), "boundary")
        rejects("more than $max") {
            CompiledDocument.platform(listOf(globs("a", rest)), emptyList(), listOf(rule), listOf(boundary), "boundary")
        }
    }

    /** RC1-38 (`PIP-12`): every header a document's conditions and rate limit keys read, whatever the effect. */
    @Test
    fun `a document knows the headers it reads`() {
        val compiler = PolicyCompiler()
        val id = PolicyId("h")
        fun statement(k: Int, effect: Effect, condition: Condition) =
            compiler.statement(id, k, StatementSource("s$k", effect, listOf("*"), condition = condition))
        val policy = CompiledPolicy(
            id,
            compiler.condition(id, Condition.Exists("http.referer.host")),
            listOf(
                statement(0, Effect.DENY, Condition.Eq("http.header.x-deny", Literal.Str("v"))),
                statement(1, Effect.ALLOW, Condition.Prefix("http.origin.host.labels", Literal.Str("example"))),
                statement(2, Effect.ALLOW, Condition.Eq("http.header.x-deny", Literal.Str("w"))),
            ),
        )
        val rule = compiler.rateLimit(
            DocumentKind.TENANT,
            "acme",
            RateLimitSource(
                "r",
                StatementSource("r", Effect.DENY, listOf("*")),
                "http.header.x-bucket",
                RateLimitSpec(1.0)
            ),
        )
        CompiledDocument.tenant(TenantId("acme"), listOf(policy), emptyList(), listOf(rule)).headersRead.assert()
            .containsExactlyInAnyOrder("referer", "x-deny", "origin", "x-bucket")
        platform().headersRead.assert().isEmpty()
        // A boundary's statements and a rate limit rule's condition read headers too (review L2 of #1200).
        val boundaryId = PolicyId("b")
        val boundary = CompiledPolicy(
            boundaryId,
            null,
            listOf(
                compiler.statement(
                    boundaryId,
                    0,
                    StatementSource(
                        "s0",
                        Effect.ALLOW,
                        listOf("*"),
                        condition = Condition.Exists("http.header.x-zone")
                    ),
                ),
            ),
        )
        val conditioned = compiler.rateLimit(
            DocumentKind.PLATFORM,
            "",
            RateLimitSource(
                "plan",
                StatementSource("plan", Effect.DENY, listOf("*"), condition = Condition.Exists("http.header.x-plan")),
                null,
                RateLimitSpec(1.0)
            ),
        )
        CompiledDocument.platform(emptyList(), emptyList(), listOf(conditioned), listOf(boundary), "b").headersRead
            .assert().containsExactlyInAnyOrder("x-zone", "x-plan")
    }

    private fun globsAllow(compiler: PolicyCompiler, statements: Int): CompiledPolicy {
        val id = PolicyId("boundary")
        return CompiledPolicy(
            id,
            null,
            (0 until statements).map { k ->
                compiler.statement(
                    id,
                    k,
                    StatementSource("s$k", Effect.ALLOW, listOf("*"), listOf(ResourceSource(GLOB)))
                )
            },
        )
    }

    private companion object {
        /** One glob segment of 10 tokens and 6 literal characters: 16 units. */
        const val GLOB = "http:path:/{v}a*a*a*a*ab"
        const val GLOB_UNITS = 16
    }
}
