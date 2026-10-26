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

import me.ahoo.cosec.engine.Guard
import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.MatcherException
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.engine.TestRule
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class FlatEngineTest {
    private val fixture = FlatFixture()
    private val emptyTenant = tenant()

    private fun decide(
        request: FlatRequest,
        platform: CompiledDocument,
        tenant: CompiledDocument? = emptyTenant,
        app: CompiledDocument? = null,
        unresolved: List<Cause> = emptyList(),
    ): Decision {
        val view = SnapshotView(platform, app, tenant, unresolved)
        val decisions = TraceLevel.entries.map { CoSecFlatEngine.evaluate(request, view, it).decision }
        // The decision never depends on the trace level (P2).
        decisions.distinct().assert().hasSize(1)
        return decisions.first()
    }

    @Test
    fun `nothing applies is an implicit deny`() {
        decide(flatRequest(), platform()).assert().isEqualTo(Decision.Deny(null))
        CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform(), null, emptyTenant)).trace.assert().isNull()
    }

    @Test
    fun `an anyone binding permits through its binding`() {
        val platform = platform(fixture.policy("p", allow()), bindings = listOf(anyone("g", "p")))
        decide(flatRequest(), platform).assert().isEqualTo(Decision.Permit(ref("p", 0, bound("g"))))
    }

    @Test
    fun `a deny wins over an allow that comes earlier`() {
        val platform = platform(fixture.policy("a", allow()), bindings = listOf(anyone("g", "a")))
        val tenant = tenant(fixture.policy("d", deny()), bindings = listOf(principal("t", "d")))
        decide(
            flatRequest(),
            platform,
            tenant
        ).assert().isEqualTo(Decision.Deny(ref("d", 0, bound("t", DocumentKind.TENANT))))
    }

    @Test
    fun `a matched deny wins over an erroring deny before it`() {
        val platform = platform(
            fixture.policy("a", deny(RuleBehavior.THROW)),
            fixture.policy("b", deny()),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b")),
        )
        decide(flatRequest(), platform).assert().isEqualTo(Decision.Deny(ref("b", 0, bound("g2"))))
    }

    @Test
    fun `an erroring deny without a matched deny is indeterminate even when an allow matches`() {
        val failing = fixture.policy("a", deny(RuleBehavior.THROW))
        val platform = platform(
            failing,
            fixture.policy("b", allow()),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b")),
        )
        val error = (failing.denies.single() as TestRule).error
        decide(flatRequest(), platform).assert()
            .isEqualTo(Decision.Indeterminate(Cause.MatcherFailed(ref("a", 0, bound("g1")), false, error)))
    }

    @Test
    fun `an erroring allow counts as no match`() {
        val platform = platform(
            fixture.policy("a", allow(RuleBehavior.THROW)),
            fixture.policy("b", allow()),
            bindings = listOf(anyone("g1", "a"), anyone("g2", "b")),
        )
        decide(flatRequest(), platform).assert().isEqualTo(Decision.Permit(ref("b", 0, bound("g2"))))
        val alone = platform(fixture.policy("a", allow(RuleBehavior.THROW)), bindings = listOf(anyone("g1", "a")))
        decide(flatRequest(), alone).assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `a failing condition fails the policy's denies and misses its allows`() {
        val guard = fixture.guard("c", GuardBehavior.THROW)
        val denying = platform(fixture.policy("p", allow(), deny(), guard = guard), bindings = listOf(anyone("g", "p")))
        decide(flatRequest(), denying).assert()
            .isEqualTo(Decision.Indeterminate(Cause.MatcherFailed(ref("p", 1, bound("g")), true, guard.error)))
        val allowing = platform(fixture.policy("p", allow(), guard = guard), bindings = listOf(anyone("g", "p")))
        decide(flatRequest(), allowing).assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `a condition that misses skips the policy`() {
        val guard = fixture.guard("c", GuardBehavior.MISS)
        val platform =
            platform(fixture.policy("p", deny(), allow(), guard = guard), bindings = listOf(anyone("g", "p")))
        decide(flatRequest(), platform).assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `a condition runs once per evaluation, also when policies share it`() {
        val guard = fixture.guard("c")
        val platform = platform(
            fixture.policy("p", deny(RuleBehavior.ACTION_MISS), allow(RuleBehavior.ACTION_MISS), guard = guard),
            fixture.policy("q", allow(RuleBehavior.ACTION_MISS), guard = guard),
            fixture.policy("r", allow(), guard = fixture.guard("other")),
            bindings = listOf(anyone("g1", "p"), anyone("g2", "q"), anyone("g3", "r"), anyone("g4", "p")),
        )
        fixture.log.clear()
        CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, emptyTenant)).decision.assert()
            .isEqualTo(Decision.Permit(ref("r", 0, bound("g3"))))
        fixture.log.calls.assert().containsExactly(
            "guard:c",
            "rule:p.0",
            "rule:p.0",
            "rule:p.1",
            "rule:p.1",
            "rule:q.0",
            "guard:other",
            "rule:r.0",
        )
    }

    @Test
    fun `a condition is not evaluated for a pass the policy has no statements in`() {
        val guard = fixture.guard("c")
        val platform = platform(
            fixture.policy("p", allow(RuleBehavior.ACTION_MISS), guard = guard),
            fixture.policy("d", deny()),
            bindings = listOf(anyone("g1", "p"), anyone("g2", "d")),
        )
        fixture.log.clear()
        CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, emptyTenant))
        fixture.log.calls.assert().containsExactly("rule:d.0")
    }

    @Test
    fun `many conditions grow the cache`() {
        // The failing condition comes first, so its error is kept while the cache grows.
        val failing = fixture.policy("a", allow(), guard = fixture.guard("failing", GuardBehavior.THROW))
        val policies = (0 until 10).map {
            fixture.policy("p$it", allow(RuleBehavior.ACTION_MISS), guard = fixture.guard("c$it"))
        }
        val all = listOf(failing) + policies + fixture.policy("y", allow())
        val platform = CompiledDocument.platform(all, all.map { anyone("g-${it.id}", it.id) })
        fixture.log.clear()
        decide(flatRequest(), platform).assert().isEqualTo(Decision.Permit(ref("y", 0, bound("g-y"))))
        fixture.log.calls.count { it.startsWith("guard:") }.assert().isEqualTo(3 * 11)
    }

    @Test
    fun `a missing attachment is indeterminate unless a deny matches`() {
        val platform = platform(fixture.policy("a", allow()), bindings = listOf(anyone("g", "a")))
        decide(flatRequest(attachments = listOf("ghost")), platform).assert()
            .isEqualTo(Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId("ghost")))))
        val denying = platform(fixture.policy("d", deny()), bindings = listOf(anyone("g", "d")))
        decide(flatRequest(attachments = listOf("ghost")), denying).assert()
            .isEqualTo(Decision.Deny(ref("d", 0, bound("g"))))
    }

    @Test
    fun `only attachable policies attach, and the root policy never does`() {
        val platform =
            platform(fixture.policy("shared", allow(), attachable = true), fixture.policy("private", allow()))
        decide(flatRequest(attachments = listOf("shared")), platform).assert()
            .isEqualTo(Decision.Permit(ref("shared", 0, BindingRef.Attached(PolicyId("shared")))))
        decide(flatRequest(attachments = listOf("private", "cosec:root", "cosec:blacklist")), platform).assert()
            .isEqualTo(
                Decision.Indeterminate(
                    Cause.MissingPolicies(
                        setOf(PolicyId("private"), PolicyId("cosec:root"), PolicyId("cosec:blacklist"))
                    ),
                ),
            )
    }

    /** RFC 0007 N2: preferring the tenant's copy would let a tenant cancel a platform DENY the token attaches. */
    @Test
    fun `an attachment whose id both the tenant and the platform define is ambiguous`() {
        val ambiguous = Decision.Indeterminate(Cause.AmbiguousPolicy(setOf(PolicyId("restricted"))))
        val platform = platform(fixture.policy("restricted", deny(), attachable = true))
        val request = flatRequest(attachments = listOf("restricted"))
        decide(request, platform, tenant(fixture.policy("restricted", allow(), attachable = true))).assert()
            .isEqualTo(ambiguous)
        // Whatever the flags: a tenant copy that cannot be attached does not hand the id to the platform either.
        decide(request, platform, tenant(fixture.policy("restricted", allow()))).assert().isEqualTo(ambiguous)
        // Alone, either copy resolves.
        decide(request, platform, tenant()).assert()
            .isEqualTo(Decision.Deny(ref("restricted", 0, BindingRef.Attached(PolicyId("restricted")))))
        decide(request, platform(), tenant(fixture.policy("restricted", allow(), attachable = true))).assert()
            .isEqualTo(Decision.Permit(ref("restricted", 0, BindingRef.Attached(PolicyId("restricted")))))
        // A DENY that matches still wins, as over a missing policy.
        val denying = platform(
            fixture.policy("restricted", allow(), attachable = true),
            fixture.policy("d", deny()),
            bindings = listOf(anyone("g", "d")),
        )
        decide(request, denying, tenant(fixture.policy("restricted", allow(), attachable = true))).assert()
            .isEqualTo(Decision.Deny(ref("d", 0, bound("g"))))
    }

    @Test
    fun `a tenant binding to a policy both the tenant and the platform define is ambiguous`() {
        val platform = platform(fixture.policy("p", deny()))
        val tenant = tenant(fixture.policy("p", allow()), bindings = listOf(anyone("t", "p")))
        decide(flatRequest(), platform, tenant).assert()
            .isEqualTo(Decision.Indeterminate(Cause.AmbiguousPolicy(setOf(PolicyId("p")))))
        // Naming only the platform's, or only its own, is not.
        decide(flatRequest(), platform, tenant(bindings = listOf(anyone("t", "p")))).assert()
            .isEqualTo(Decision.Deny(ref("p", 0, bound("t", DocumentKind.TENANT))))
        decide(flatRequest(), platform(), tenant).assert()
            .isEqualTo(Decision.Permit(ref("p", 0, bound("t", DocumentKind.TENANT))))
        // An app document's own policies belong to the platform layer (RFC 0004 §4.3): no ambiguity there.
        val app = app(fixture.policy("p", allow()), bindings = listOf(role("r", "editor", "p")))
        decide(flatRequest(roles = listOf("editor")), platform(fixture.policy("p", allow())), tenant(), app).assert()
            .isEqualTo(Decision.Permit(ref("p", 0, bound("r", DocumentKind.APP))))
    }

    @Test
    fun `an anonymous principal has no attachments, principal or break-glass bindings`() {
        val platform = platform(
            fixture.policy("p", allow(), attachable = true),
            bindings = listOf(principal("b", "p", tenant = "(0)"), root(principal = "alice")),
        )
        decide(
            flatRequest(authenticated = false, attachments = listOf("p", "ghost")),
            platform,
            tenant(tenant = "(0)")
        ).assert()
            .isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `a blocked request is denied by the blacklist whatever else is unresolved`() {
        val failing = platform(fixture.policy("x", deny(RuleBehavior.THROW)), bindings = listOf(anyone("g", "x")))
        val unavailable = listOf(Cause.SourceUnavailable(DocumentKind.TENANT, MatcherException("redis")))
        decide(flatRequest(blocked = true, attachments = listOf("ghost")), failing, null, null, unavailable).assert()
            .isEqualTo(Decision.Deny(blacklistRef))
    }

    @Test
    fun `what the caller could not read is indeterminate unless a deny matches`() {
        val cause = Cause.SourceUnavailable(DocumentKind.APP, MatcherException("redis"))
        val allowing = platform(fixture.policy("a", allow()), bindings = listOf(anyone("g", "a")))
        decide(flatRequest(), allowing, unresolved = listOf(cause)).assert().isEqualTo(Decision.Indeterminate(cause))
        val denying = platform(fixture.policy("d", deny()), bindings = listOf(anyone("g", "d")))
        decide(
            flatRequest(),
            denying,
            unresolved = listOf(cause)
        ).assert().isEqualTo(Decision.Deny(ref("d", 0, bound("g"))))
    }

    @Test
    fun `a document the request needs and was not given fails closed`() {
        val allowing = platform(fixture.policy("a", allow()), bindings = listOf(anyone("g", "a")))
        decide(flatRequest(), allowing, tenant = null).assert()
            .isEqualTo(Decision.Indeterminate(Cause.NotLoaded(DocumentKind.TENANT)))
        decide(flatRequest(roles = listOf("editor")), allowing, app = null).assert()
            .isEqualTo(Decision.Indeterminate(Cause.NotLoaded(DocumentKind.APP)))
        // Without roles, or without an app, no app document is needed.
        decide(flatRequest(), allowing, app = null).assert().isInstanceOf(Decision.Permit::class.java)
        decide(flatRequest(app = "", roles = listOf("editor")), allowing, app = null).assert()
            .isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `a document of another scope is rejected`() {
        assertThrownBy<IllegalArgumentException> {
            CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform(), null, tenant(tenant = "beta")))
        }
        assertThrownBy<IllegalArgumentException> {
            CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform(), app(app = "other"), emptyTenant))
        }
    }

    @Test
    fun `root is a break-glass binding that permits everything but every deny`() {
        val rootRequest = flatRequest(tenant = "(platform)", principal = "cosec")
        val rootRef = StatementRef.Policy(BuiltinPolicies.ROOT, 0, "root", bound("root"))
        decide(rootRequest, platform(bindings = listOf(root())), tenant(tenant = "(platform)")).assert()
            .isEqualTo(Decision.Permit(rootRef))
        val denying = platform(fixture.policy("d", deny()), bindings = listOf(root(), anyone("g", "d")))
        decide(
            rootRequest,
            denying,
            tenant(tenant = "(platform)")
        ).assert().isEqualTo(Decision.Deny(ref("d", 0, bound("g"))))
        // Expired, or for a principal of the same id in another tenant, it does not apply.
        decide(
            flatRequest(tenant = "(platform)", principal = "cosec", time = NOW + 1),
            platform(bindings = listOf(root())),
            tenant(tenant = "(platform)")
        )
            .assert().isEqualTo(Decision.Deny(null))
        decide(
            flatRequest(principal = "cosec"),
            platform(bindings = listOf(root()))
        ).assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `principal bindings apply to their principal in their tenant`() {
        val platform = platform(
            fixture.policy("p", allow()),
            bindings = listOf(principal("b1", "p", tenant = "beta"), principal("b2", "p", principal = "bob")),
        )
        decide(flatRequest(), platform).assert().isEqualTo(Decision.Deny(null))
        decide(flatRequest(principal = "bob"), platform).assert().isEqualTo(Decision.Permit(ref("p", 0, bound("b2"))))
    }

    @Test
    fun `role bindings apply in their app and space, to roles the principal has there`() {
        val policies = arrayOf(fixture.policy("perm:app/read", allow()))
        val app = app(
            *policies,
            bindings = listOf(
                role("r1", "editor", "perm:app/read", space = "team-a"),
                role("r3", "viewer", "perm:app/read", tenant = "beta"),
                role("r4", "viewer", "perm:app/read", space = null, tenant = "acme"),
            ),
        )
        decide(flatRequest(roles = listOf("editor")), platform(), app = app).assert().isEqualTo(Decision.Deny(null))
        decide(flatRequest(roles = listOf("editor"), space = "team-a"), platform(), app = app).assert()
            .isEqualTo(Decision.Permit(ref("perm:app/read", 0, bound("r1", DocumentKind.APP))))
        decide(flatRequest(roles = listOf("viewer"), space = "team-a"), platform(), app = app).assert()
            .isEqualTo(Decision.Permit(ref("perm:app/read", 0, bound("r4", DocumentKind.APP))))
        decide(
            flatRequest(roles = listOf("viewer"), tenant = "beta"),
            platform(),
            tenant(tenant = "beta"),
            app
        ).assert()
            .isEqualTo(Decision.Permit(ref("perm:app/read", 0, bound("r3", DocumentKind.APP))))
    }

    @Test
    fun `app and tenant bindings may name platform policies, and a missing one fails closed`() {
        val platform = platform(fixture.policy("shared", allow()))
        val app = app(bindings = listOf(role("r", "editor", "shared")))
        decide(flatRequest(roles = listOf("editor")), platform, app = app).assert()
            .isEqualTo(Decision.Permit(ref("shared", 0, bound("r", DocumentKind.APP))))
        val tenant = tenant(bindings = listOf(anyone("t", "gone")))
        decide(flatRequest(roles = listOf("editor")), platform, tenant, app).assert()
            .isEqualTo(Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId("gone")))))
    }

    @Test
    fun `without errors the first match in candidate order is reported`() {
        val platform = platform(
            fixture.policy("z", allow()),
            fixture.policy("a", allow(), attachable = true),
            bindings = listOf(anyone("g", "z")),
        )
        val tenant = tenant(fixture.policy("t", allow()), bindings = listOf(anyone("t1", "t")))
        // Platform anyone, then tenant anyone, then attachments.
        decide(flatRequest(attachments = listOf("a")), platform, tenant).assert()
            .isEqualTo(Decision.Permit(ref("z", 0, bound("g"))))
    }

    @Test
    fun `a statement without a match result fails`() {
        val broken = object : Rule {
            override val effect = Effect.DENY
            override val index = 0
            override val name = "broken"
            override val id: String? = null

            override fun match(request: AuthzRequest): RuleMatch = javaNull()
        }
        val platform =
            platform(CompiledPolicy(PolicyId("b"), null, listOf(broken)), bindings = listOf(anyone("g", "b")))
        for (level in TraceLevel.entries) {
            val decision = CoSecFlatEngine.evaluate(
                flatRequest(),
                SnapshotView(platform, null, emptyTenant),
                level
            ).decision
            decision.assert().isInstanceOf(Decision.Indeterminate::class.java)
            ((decision as Decision.Indeterminate).cause as Cause.MatcherFailed).error.assert()
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `an Error propagates`() {
        val rule = object : Rule {
            override val effect = Effect.DENY
            override val index = 0
            override val name = "oom"
            override val id: String? = null

            override fun match(request: AuthzRequest): RuleMatch = throw OutOfMemoryError("test")
        }
        val platform = platform(CompiledPolicy(PolicyId("b"), null, listOf(rule)), bindings = listOf(anyone("g", "b")))
        for (level in TraceLevel.entries) {
            assertThrownBy<OutOfMemoryError> {
                CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, emptyTenant), level)
            }
        }
        val guarded = platform(
            CompiledPolicy(PolicyId("g"), ThrowingGuard, listOf(rule)),
            bindings = listOf(anyone("g", "g")),
        )
        assertThrownBy<StackOverflowError> {
            CoSecFlatEngine.evaluate(
                flatRequest(),
                SnapshotView(guarded, null, emptyTenant)
            )
        }
    }
}

/** What a Java matcher returns when it breaks its contract. */
@Suppress("UNCHECKED_CAST")
private fun <T> javaNull(): T = null as T

private object ThrowingGuard : Guard {
    override fun matches(request: AuthzRequest): Boolean = throw StackOverflowError()
}
