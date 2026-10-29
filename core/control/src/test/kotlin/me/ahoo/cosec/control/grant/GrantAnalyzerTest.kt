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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.control.grant.GrantFixtures.ACME
import me.ahoo.cosec.control.grant.GrantFixtures.TENANT_ADMIN
import me.ahoo.cosec.control.grant.GrantFixtures.allow
import me.ahoo.cosec.control.grant.GrantFixtures.and
import me.ahoo.cosec.control.grant.GrantFixtures.bind
import me.ahoo.cosec.control.grant.GrantFixtures.cidr
import me.ahoo.cosec.control.grant.GrantFixtures.deny
import me.ahoo.cosec.control.grant.GrantFixtures.eq
import me.ahoo.cosec.control.grant.GrantFixtures.eqAttr
import me.ahoo.cosec.control.grant.GrantFixtures.grantor
import me.ahoo.cosec.control.grant.GrantFixtures.policy
import me.ahoo.cosec.control.grant.GrantFixtures.principal
import me.ahoo.cosec.control.grant.GrantFixtures.role
import me.ahoo.cosec.control.grant.GrantFixtures.snapshot
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Every row of the example table of RFC 0007 §7.4.6, then the rest of §7.4.1–§7.4.4 case by case. */
class GrantAnalyzerTest {
    private val analyzer = GrantAnalyzer()

    private fun verdicts(grantor: Grantor, snapshot: GrantSnapshot, candidate: GrantDocument): List<GrantVerdict> =
        analyzer.analyze(grantor, snapshot, GrantChange(ACME, candidate)).verdicts.map { it.second }

    private fun List<GrantVerdict>.assertContained() {
        assert().isNotEmpty()
        forEach { it.assert().describedAs("$it").isInstanceOf(GrantVerdict.Contained::class.java) }
    }

    private fun List<GrantVerdict>.assertUnknown(reason: UnknownReason, shortfall: Shortfall? = null) {
        assert().isNotEmpty()
        forEach {
            it.assert().describedAs("$it").isInstanceOf(GrantVerdict.Unknown::class.java)
            (it as GrantVerdict.Unknown).reason.assert().isEqualTo(reason)
            if (shortfall != null) it.shortfall.assert().describedAs("$it").isEqualTo(shortfall)
        }
    }

    /** §7.4.6 rows 1–3: `tenant-admin`, and `order:*` on every order through role `ops` (bound in space `*`), held in team-a. */
    @Nested
    inner class RoleInOneSpace {
        private val gina = grantor(roles = mapOf("" to listOf("admin"), "team-a" to listOf("ops")))
        private val published = GrantDocument(
            policies = listOf(TENANT_ADMIN, policy("orders", allow("order:*", "order/**"))),
            bindings = listOf(
                bind("admin", role("admin"), "cosec:tenant-admin", app = "cosec-control", space = ""),
                bind("ops", role("ops"), "orders"),
            ),
        )
        private val snapshot = snapshot(tenant = published)

        private fun grant(app: String, space: String, condition: Condition? = null) = GrantDocument(
            policies = published.policies + policy("viewer", allow("order:read", "order/{id}", condition)),
            bindings = published.bindings + bind("viewer", role("viewer"), "viewer", app, space),
        )

        @Test
        fun `row 1 - granting read on one order in the space the grantor holds the role in is contained`() {
            verdicts(gina, snapshot, grant("order", "team-a")).assertContained()
        }

        @Test
        fun `row 2 - the same grant in every space is not covered - the grantor holds it only in team-a`() {
            verdicts(gina, snapshot, grant("order", "*")).assertUnknown(UnknownReason.NOT_COVERED, Shortfall.SCOPE)
        }

        @Test
        fun `row 3 - read only your own order is contained - the grantor's condition is true`() {
            verdicts(
                gina,
                snapshot,
                grant("order", "team-a", eqAttr("resource.var.id", "subject.id"))
            ).assertContained()
        }
    }

    /** §7.4.6 rows 4–5: `order:read` on one order segment within 10.0.0.0/8. */
    @Nested
    inner class ConditionedAllow {
        private val gina = grantor()
        private val internal = cidr("environment.ip", "10.0.0.0/8")
        private val snapshot = snapshot(
            tenant = GrantDocument(
                policies = listOf(policy("internal", allow("order:read", "order/*", internal))),
                bindings = listOf(bind("gina", principal("gina"), "internal")),
            )
        )

        private fun grant(condition: Condition?) = GrantDocument(
            policies = listOf(
                policy("internal", allow("order:read", "order/*", internal)),
                policy("g", allow("order:read", "order/*", condition))
            ),
            bindings = listOf(bind("gina", principal("gina"), "internal"), bind("g", role("viewer"), "g")),
        )

        @Test
        fun `row 4 - the grantor's condition and one more is contained`() {
            verdicts(gina, snapshot, grant(and(internal, eq("scope.space", "team-a")))).assertContained()
        }

        @Test
        fun `row 5 - without the condition it is not covered and goes to approval`() {
            val analysis = analyzer.analyze(gina, snapshot, GrantChange(ACME, grant(null)))
            analysis.verdicts.map { it.second }.assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
            analysis.needsApproval.assert().isTrue()
            ((analysis.verdicts.single().second as GrantVerdict.Unknown).closest!!.policy).assert().isEqualTo(
                PolicyId("internal")
            )
        }
    }

    @Test
    fun `row 6 - an allow held only through a role in team-a does not cover every space`() {
        val gina = grantor(roles = mapOf("team-a" to listOf("reader")))
        val published = GrantDocument(
            policies = listOf(policy("read", allow("order:read", "order/*"))),
            bindings = listOf(bind("reader", role("reader"), "read", space = "team-a")),
        )
        val candidate = GrantDocument(
            policies = published.policies,
            bindings = published.bindings + bind("all", role("viewer"), "read", space = "*"),
        )
        verdicts(
            gina,
            snapshot(tenant = published),
            candidate
        ).assertUnknown(UnknownReason.NOT_COVERED, Shortfall.SCOPE)
    }

    @Test
    fun `row 7 - a principal-bound deny of the grantor overlaps the fragment`() {
        val published = GrantDocument(
            policies = listOf(policy("orders", allow("order:*")), policy("no-delete", deny("order:delete"))),
            bindings = listOf(bind("all", principal("gina"), "orders"), bind("nd", principal("gina"), "no-delete")),
        )
        val candidate = GrantDocument(
            policies = published.policies + policy("delete", allow("order:delete")),
            bindings = published.bindings + bind("d", role("viewer"), "delete"),
        )
        verdicts(grantor(), snapshot(tenant = published), candidate).assertUnknown(UnknownReason.DENY_OVERLAP)
    }

    /**
     * RFC 0009 §4.2, review N1: a grantor with a capped credential (a CI key) holds nothing bound to its principal by
     * name, so it cannot lend it: binding it to a role the principal holds would hand the key what its cap withholds.
     */
    @Test
    fun `a capped grantor does not hold its principal bindings`() {
        val published = GrantDocument(
            policies = listOf(policy("orders", allow("order:*"))),
            bindings = listOf(bind("p:gina", principal("gina"), "orders")),
        )
        val candidate = GrantDocument(
            policies = published.policies,
            bindings = published.bindings + bind("editor-orders", role("policy-editor"), "orders"),
        )
        val token = grantor(roles = mapOf("" to listOf("policy-editor")))
        verdicts(token, snapshot(tenant = published), candidate).assertContained()
        val key = grantor(roles = mapOf("" to listOf("policy-editor")), principalBindings = false)
        val analysis = analyzer.analyze(key, snapshot(tenant = published), GrantChange(ACME, candidate))
        analysis.verdicts.map { it.second }.assertUnknown(UnknownReason.NOT_COVERED, Shortfall.NO_ALLOW)
        analysis.needsApproval.assert().isTrue()
    }

    @Test
    fun `row 8 - an anyone deny binds the grantee just as the grantor and does not count`() {
        val platform = GrantDocument(
            policies = listOf(
                policy(
                    "clearance",
                    deny("order:export", condition = Condition.Not(eq("subject.attributes.clearance", "high")))
                ),
            ),
            bindings = listOf(bind("clearance", SubjectRef.Anyone, "clearance")),
        )
        val published = GrantDocument(
            policies = listOf(policy("orders", allow("order:*"))),
            bindings = listOf(bind("all", principal("gina"), "orders")),
        )
        val candidate = GrantDocument(
            policies = published.policies + policy("export", allow("order:export")),
            bindings = published.bindings + bind("e", role("viewer"), "export"),
        )
        verdicts(grantor(), snapshot(platform, published), candidate).assertContained()
    }

    @Test
    fun `row 9 - tenant-admin granted in the same scope is contained once subject tenant is the tenant`() {
        val platform = GrantDocument(policies = listOf(TENANT_ADMIN))
        val published = GrantDocument(
            bindings = listOf(bind("admin", role("admin"), "cosec:tenant-admin", app = "cosec-control", space = "")),
        )
        val candidate = GrantDocument(
            bindings = published.bindings + bind("ops2", role("ops2"), "cosec:tenant-admin", app = "cosec-control", space = ""),
        )
        val verdicts = verdicts(grantor(roles = mapOf("" to listOf("admin"))), snapshot(platform, published), candidate)
        verdicts.size.assert().isEqualTo(16)
        verdicts.assertContained()
    }

    @Test
    fun `row 10 - cosec star on all documents is not contained in tenant-admin`() {
        val platform = GrantDocument(policies = listOf(TENANT_ADMIN))
        val published = GrantDocument(
            bindings = listOf(bind("admin", role("admin"), "cosec:tenant-admin", app = "cosec-control", space = "")),
        )
        val candidate = GrantDocument(
            policies = listOf(policy("everything", allow("cosec:*", "doc/**"))),
            bindings = published.bindings + bind("me", principal("gina"), "everything", app = "cosec-control", space = ""),
        )
        verdicts(grantor(roles = mapOf("" to listOf("admin"))), snapshot(platform, published), candidate)
            .assertUnknown(UnknownReason.NOT_COVERED)
        // Even cosec:read alone on every document is more than doc/tenant/{t}/** holds.
        val read = GrantDocument(
            policies = listOf(policy("read-all", allow("cosec:read", "doc/**"))),
            bindings = published.bindings + bind("me", principal("gina"), "read-all", app = "cosec-control", space = ""),
        )
        verdicts(grantor(roles = mapOf("" to listOf("admin"))), snapshot(platform, published), read)
            .assertUnknown(UnknownReason.NOT_COVERED, Shortfall.RESOURCE)
    }

    @Nested
    inner class Fragments {
        private val published = GrantDocument(
            policies = listOf(policy("p", allow("order:read", "order/*")), policy("q", allow("order:read"))),
            bindings = listOf(bind("b", role("viewer"), "p")),
        )
        private val snapshot = snapshot(tenant = published)

        private fun fragments(candidate: GrantDocument) = analyzer.fragments(snapshot, GrantChange(ACME, candidate))

        @Test
        fun `an unchanged document grants nothing`() {
            fragments(published).assert().isEmpty()
        }

        @Test
        fun `actions and resources split into one fragment each`() {
            val candidate = GrantDocument(
                policies = listOf(
                    policy("p", allow(listOf("order:read", "order:list"), listOf("order/*", "invoice/*")))
                ),
                bindings = published.bindings,
            )
            fragments(candidate).map { "${it.action} ${it.resource}" }.assert()
                .containsExactly(
                    "order:read order/*",
                    "order:read invoice/*",
                    "order:list order/*",
                    "order:list invoice/*"
                )
        }

        @Test
        fun `narrowing an allow grants nothing, widening it grants it to every binding`() {
            val narrowed =
                GrantDocument(
                    policies = listOf(policy("p", allow("order:read", "order/a")), published.policies[1]),
                    bindings = published.bindings
                )
            fragments(narrowed).assert().isEmpty()
            val widened =
                GrantDocument(
                    policies = listOf(policy("p", allow("order:*", "order/*")), published.policies[1]),
                    bindings = published.bindings
                )
            fragments(widened).single().grantee.toString().assert().isEqualTo("binding b")
        }

        @Test
        fun `removing bindings and allows and adding denies grants nothing`() {
            fragments(GrantDocument()).assert().isEmpty()
            val denied = GrantDocument(
                policies = listOf(
                    policy("p", allow("order:read", "order/*"), deny("order:read", "order/secret")),
                    published.policies[1]
                ),
                bindings = published.bindings,
            )
            fragments(denied).assert().isEmpty()
        }

        @Test
        fun `a changed binding grants every allow of its policy`() {
            val moved =
                GrantDocument(
                    policies = published.policies,
                    bindings = listOf(bind("b", role("viewer"), "p", space = "team-b"))
                )
            fragments(moved).single().scope.assert()
                .isEqualTo(ScopeSet(Dimension.Every(), Dimension.Only(setOf(SpaceId("team-b")))))
        }

        @Test
        fun `making a policy attachable grants all of it to attachments in every scope`() {
            val attachable = GrantDocument(
                policies = listOf(published.policies[0], policy("q", allow("order:read"), attachable = true)),
                bindings = published.bindings,
            )
            val fragment = fragments(attachable).single()
            fragment.grantee.assert().isEqualTo(Grantee.Attaching)
            fragment.scope.assert().isEqualTo(ScopeSet.EVERY)
        }

        @Test
        fun `an expired binding receives nothing from a widened policy`() {
            val expired =
                Binding(BindingId("old"), role("viewer"), GrantFixtures.scope(), PolicyId("p"), expiresAt = 1L)
            val before = GrantDocument(policies = published.policies, bindings = published.bindings + expired)
            val widened = GrantDocument(policies = listOf(policy("p", allow("order:*"))), bindings = before.bindings)
            analyzer.fragments(snapshot(tenant = before), GrantChange(ACME, widened)).map { it.grantee.toString() }
                .assert().containsExactly("binding b")
        }

        @Test
        fun `a binding to a policy that resolves nowhere grants nothing`() {
            fragments(
                GrantDocument(
                    policies = published.policies,
                    bindings = published.bindings + bind("x", role("viewer"), "missing")
                )
            )
                .assert().isEmpty()
        }
    }

    @Nested
    inner class Protection {
        private val published = GrantDocument(
            policies = listOf(
                policy("guard", deny("order:delete"), protected = true),
                policy("free", deny("order:list"))
            ),
            bindings = listOf(bind("g", SubjectRef.Anyone, "guard"), bind("f", SubjectRef.Anyone, "free")),
            protectedBindings = setOf(BindingId("g")),
        )

        private fun unprotect(candidate: GrantDocument) =
            analyzer.analyze(grantor(), snapshot(tenant = published), GrantChange(ACME, candidate)).unprotect

        @Test
        fun `removing or relaxing an unprotected deny needs nothing`() {
            unprotect(
                GrantDocument(
                    policies = listOf(published.policies[0]),
                    bindings = published.bindings,
                    protectedBindings = published.protectedBindings
                )
            )
                .assert().isEmpty()
        }

        @Test
        fun `changing a protected deny or its binding needs cosec unprotect`() {
            unprotect(
                GrantDocument(
                    policies = listOf(policy("free", deny("order:list"))),
                    bindings = listOf(published.bindings[1])
                )
            )
                .assert().containsExactlyInAnyOrder(
                    ProtectedEntry.Policy(PolicyId("guard")),
                    ProtectedEntry.Binding(BindingId("g"))
                )
            unprotect(
                GrantDocument(
                    policies = listOf(policy("guard", deny("order:delete")), published.policies[1]),
                    bindings = published.bindings
                )
            )
                .assert().containsExactlyInAnyOrder(
                    ProtectedEntry.Policy(PolicyId("guard")),
                    ProtectedEntry.Binding(BindingId("g"))
                )
        }

        @Test
        fun `keeping protected entries as they are needs nothing`() {
            unprotect(published).assert().isEmpty()
        }
    }

    @Nested
    inner class Soundness {
        private val published = GrantDocument(
            policies = listOf(policy("orders", allow("order:*", "order/**"))),
            bindings = listOf(bind("gina", principal("gina"), "orders")),
        )

        private fun grantWith(statement: GrantStatement, publishedDocument: GrantDocument = published, gina: Grantor = grantor()) =
            verdicts(
                gina,
                snapshot(tenant = publishedDocument),
                GrantDocument(
                    policies = publishedDocument.policies + GrantPolicy(PolicyId("g"), listOf(statement)),
                    bindings = publishedDocument.bindings + bind("g", role("viewer"), "g"),
                ),
            )

        @Test
        fun `a grantor of another tenant is never contained`() {
            grantWith(allow("order:read", "order/1"), gina = grantor(tenant = TenantId("other")))
                .assertUnknown(UnknownReason.CROSS_TENANT)
        }

        @Test
        fun `trust root changes are not analyzed`() {
            val analysis = analyzer.analyze(
                grantor(),
                snapshot(),
                GrantChange(
                    ACME,
                    GrantDocument(
                        policies = listOf(policy("p", allow("*"))),
                        bindings = listOf(bind("b", role("x"), "p"))
                    )
                ),
                trustRoot = true,
            )
            analysis.verdicts.single().second.assert().isEqualTo(GrantVerdict.TrustRoot)
            analysis.needsApproval.assert().isFalse()
        }

        @Test
        fun `a grantor without any allow covers nothing`() {
            grantWith(allow("order:read"), GrantDocument()).assertUnknown(UnknownReason.NOT_COVERED, Shortfall.NO_ALLOW)
        }

        @Test
        fun `a grantor condition on subject attributes holds when the grantor's own attributes satisfy it`() {
            val held = GrantDocument(
                policies = listOf(policy("eu", allow("order:read", "order/*", eq("subject.attributes.region", "eu")))),
                bindings = listOf(bind("gina", principal("gina"), "eu")),
            )
            grantWith(
                allow("order:read", "order/1"),
                held,
                grantor(attributes = mapOf("region" to "eu"))
            ).assertContained()
            grantWith(allow("order:read", "order/1"), held, grantor(attributes = mapOf("region" to "us")))
                .assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
            grantWith(
                allow("order:read", "order/1"),
                held
            ).assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
        }

        @Test
        fun `a grantor condition on an external attribute is never comparable`() {
            val external = eq("ext.risk.score", "low")
            val held = GrantDocument(
                policies = listOf(policy("ext", allow("order:read", "order/*", external))),
                bindings = listOf(bind("gina", principal("gina"), "ext")),
            )
            grantWith(
                allow("order:read", "order/1", external),
                held
            ).assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
        }

        @Test
        fun `a grantor condition on a variable needs the fragment to capture it at the same position`() {
            val own = eqAttr("resource.var.owner", "subject.id")
            val held = GrantDocument(
                policies = listOf(policy("own", allow("doc:read", "doc/{owner}/**", own))),
                bindings = listOf(bind("gina", principal("gina"), "own")),
            )
            grantWith(allow("doc:read", "doc/{o}/x", eq("resource.var.o", "gina")), held).assertContained()
            grantWith(
                allow("doc:read", "doc/gina/x"),
                held
            ).assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
            grantWith(allow("doc:read", "doc/{o}/x", eq("resource.var.o", "bob")), held)
                .assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
        }

        @Test
        fun `a variable read through overlapping resources of the grantor is not comparable`() {
            val own = eqAttr("resource.var.owner", "subject.id")
            fun held(vararg resources: String) = GrantDocument(
                policies = listOf(policy("own", allow(listOf("doc:read"), resources.toList(), own))),
                bindings = listOf(bind("gina", principal("gina"), "own")),
            )
            val grant = allow("doc:read", "doc/{o}/y", eq("resource.var.o", "gina"))
            grantWith(grant, held("doc/{owner}/**", "file/{owner}")).assertContained()
            // doc/x/y matches both of the grantor's resources, which capture different values there.
            grantWith(
                grant,
                held("doc/{owner}/**", "doc/x/{owner}")
            ).assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
        }

        @Test
        fun `scope roles are substituted only where the grantor has one set of roles`() {
            val held = GrantDocument(
                policies = listOf(
                    policy(
                        "ops",
                        allow(listOf("order:read"), null, Condition.Contains(Literal.Str("ops"), "scope.roles"))
                    )
                ),
                bindings = listOf(bind("gina", principal("gina"), "ops")),
            )
            val gina = grantor(roles = mapOf("team-a" to listOf("ops"), "team-b" to listOf("dev")))
            fun grant(space: String) = verdicts(
                gina,
                snapshot(tenant = held),
                GrantDocument(
                    policies = held.policies + policy("g", allow("order:read")),
                    bindings = held.bindings + bind("g", role("viewer"), "g", space = space),
                ),
            )
            grant("team-a").assertContained()
            grant("team-b").assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
            grant("*").assertUnknown(UnknownReason.NOT_COVERED, Shortfall.CONDITION)
        }

        @Test
        fun `a fragment whose condition rules out its own scope never applies`() {
            val verdict = grantWith(allow("user:delete", condition = eq("scope.app", "*"))).single()
            (verdict as GrantVerdict.Contained).by.assert().isNull()
        }

        @Test
        fun `a missing policy bound to the grantor counts as a deny of everything`() {
            val held = GrantDocument(
                policies = published.policies,
                bindings = published.bindings + bind("lost", principal("gina"), "gone"),
            )
            grantWith(allow("order:read", "order/1"), held).assertUnknown(UnknownReason.DENY_OVERLAP)
            val viaAnyone =
                GrantDocument(
                    policies = published.policies,
                    bindings = published.bindings + bind("lost", SubjectRef.Anyone, "gone")
                )
            grantWith(allow("order:read", "order/1"), viaAnyone).assertContained()
        }

        @Test
        fun `a deny on another action or another resource does not overlap`() {
            val held = GrantDocument(
                policies = published.policies + policy("d", deny("order:delete"), deny("order:*", "order/secret/**")),
                bindings = published.bindings + bind("d", principal("gina"), "d"),
            )
            grantWith(allow("order:read", "order/public"), held).assertContained()
            grantWith(allow("order:read", "order/secret/1"), held).assertUnknown(UnknownReason.DENY_OVERLAP)
        }

        @Test
        fun `attachments the grantor makes are held in every scope`() {
            val held = GrantDocument(policies = listOf(policy("att", allow("order:read"), attachable = true)))
            grantWith(allow("order:read", "order/1"), held, grantor(attachments = listOf("att"))).assertContained()
            val notAttachable = GrantDocument(policies = listOf(policy("att", allow("order:read"))))
            grantWith(allow("order:read", "order/1"), notAttachable, grantor(attachments = listOf("att")))
                .assertUnknown(UnknownReason.NOT_COVERED)
        }
    }

    @Nested
    inner class Narrowing {
        @Test
        fun `a narrower statement narrows, a wider one does not`() {
            val base = allow("order:*", "order/**", cidr("environment.ip", "10.0.0.0/8"))
            val narrower = allow("order:read", "order/1", cidr("environment.ip", "10.1.0.0/16"))
            analyzer.narrows(narrower, narrower.condition, base, base.condition).assert().isTrue()
            analyzer.narrows(allow("order:read", "order/1"), null, base, base.condition).assert().isFalse()
            analyzer.narrows(allow("user:read"), null, allow("order:*"), null).assert().isFalse()
            analyzer.narrows(deny("order:read"), null, allow("order:*"), null).assert().isFalse()
        }
    }
}
