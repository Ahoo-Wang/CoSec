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

package me.ahoo.cosec.engine

import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.Effect.ALLOW
import me.ahoo.cosec.model.Effect.DENY
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.LayerKind.GLOBAL
import me.ahoo.cosec.model.LayerKind.PRINCIPAL
import me.ahoo.cosec.model.LayerKind.ROLE
import me.ahoo.cosec.model.StatementRef
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import me.ahoo.cosec.engine.GuardBehavior as G
import me.ahoo.cosec.engine.RuleBehavior as R

/** The combining algorithm, case by case against RFC 0002 §4.2 (S1–S14). The decision is checked at every level. */
class CoSecEngineTest {
    private val fixture = Fixture()
    private val log = fixture.log

    private fun decide(policies: PolicySet, blocked: Boolean = false, root: Boolean = false): Decision {
        val decisions = TraceLevel.entries.map {
            log.clear()
            CoSecEngine.evaluate(request(blocked, root), policies, it).decision
        }
        // Leave the untraced run's calls in the log.
        log.clear()
        val untraced = CoSecEngine.evaluate(request(blocked, root), policies)
        untraced.trace.assert().isNull()
        decisions.assert().allMatch { it == untraced.decision }
        return untraced.decision
    }

    private fun stmt(layer: me.ahoo.cosec.model.LayerKind, policy: String, index: Int) =
        StatementRef.PolicyStatement(layer, policy, index, "$policy.$index")

    private fun allowAll(kind: me.ahoo.cosec.model.LayerKind, id: String) =
        loaded(kind, fixture.policy(id, ALLOW to R.MATCH))

    private fun denyAll(kind: me.ahoo.cosec.model.LayerKind, id: String) =
        loaded(kind, fixture.policy(id, DENY to R.MATCH))

    @Test
    fun `S1 blacklist denies before root and reads nothing`() {
        val policies = PolicySet(listOf(allowAll(GLOBAL, "g")))
        CoSecEngine.requiresPolicies(request(blocked = true, root = true)).assert().isFalse()
        decide(policies, blocked = true, root = true).assert().isEqualTo(Decision.Deny(StatementRef.Blacklist))
        log.calls.assert().isEmpty()
    }

    @Test
    fun `S2 root permits without evaluating any layer`() {
        val policies = PolicySet(listOf(denyAll(GLOBAL, "g")))
        CoSecEngine.requiresPolicies(request(root = true)).assert().isFalse()
        decide(policies, root = true).assert().isEqualTo(Decision.Permit(StatementRef.Root))
        log.calls.assert().isEmpty()
    }

    @Test
    fun `policies are required unless blocked or root`() {
        CoSecEngine.requiresPolicies(request()).assert().isTrue()
    }

    @Test
    fun `S7 nothing matched is an implicit deny`() {
        decide(PolicySet.NOT_LOADED).assert().isEqualTo(Decision.Deny(null))
        val policies = PolicySet(
            listOf(loaded(GLOBAL, fixture.policy("g", ALLOW to R.ACTION_MISS, DENY to R.CONDITION_MISS)))
        )
        decide(policies).assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `S9 S10 absent layers have no result`() {
        val policies = PolicySet(
            listOf(
                LayerInput.Absent(GLOBAL, AbsentReason.NO_POLICIES),
                allowAll(PRINCIPAL, "p"),
                LayerInput.Absent(ROLE, AbsentReason.NO_ROLE_IN_SPACE),
            ),
        )
        decide(policies).assert().isEqualTo(Decision.Permit(stmt(PRINCIPAL, "p", 0)))
    }

    @Test
    fun `S4 every guard of a layer runs before any rule`() {
        val policies = PolicySet(
            listOf(
                loaded(
                    GLOBAL,
                    fixture.policy("a", ALLOW to R.ACTION_MISS),
                    fixture.policy("b", ALLOW to R.MATCH, guard = G.MISS),
                    fixture.policy("c", DENY to R.ACTION_MISS, ALLOW to R.MATCH),
                ),
            ),
        )
        decide(policies).assert().isEqualTo(Decision.Permit(stmt(GLOBAL, "c", 1)))
        log.calls.assert().containsExactly("guard:a", "guard:b", "guard:c", "rule:c.0", "rule:a.0", "rule:c.1")
    }

    @Test
    fun `S5 a deny later in source order beats an earlier allow in the same layer`() {
        val policies = PolicySet(
            listOf(
                loaded(
                    GLOBAL,
                    fixture.policy("a", ALLOW to R.MATCH, DENY to R.ACTION_MISS),
                    fixture.policy("b", ALLOW to R.MATCH, DENY to R.MATCH, DENY to R.MATCH),
                ),
            ),
        )
        decide(policies).assert().isEqualTo(Decision.Deny(stmt(GLOBAL, "b", 1)))
        log.calls.assert().containsExactly("guard:a", "guard:b", "rule:a.1", "rule:b.1")
    }

    @Test
    fun `S5 the first matching allow in group then rule order decides`() {
        val policies = PolicySet(
            listOf(
                loaded(
                    GLOBAL,
                    fixture.policy("a", ALLOW to R.ACTION_MISS, ALLOW to R.MATCH),
                    fixture.policy("b", ALLOW to R.MATCH),
                ),
            ),
        )
        decide(policies).assert().isEqualTo(Decision.Permit(stmt(GLOBAL, "a", 1)))
    }

    @Test
    fun `S6 a later layer's deny overrides an earlier allow`() {
        val policies = PolicySet(listOf(allowAll(GLOBAL, "g"), denyAll(PRINCIPAL, "p")))
        decide(policies).assert().isEqualTo(Decision.Deny(stmt(PRINCIPAL, "p", 0)))
    }

    @Test
    fun `S6 the first layer's allow is kept over a later allow`() {
        val policies = PolicySet(listOf(allowAll(GLOBAL, "g"), allowAll(PRINCIPAL, "p")))
        decide(policies).assert().isEqualTo(Decision.Permit(stmt(GLOBAL, "g", 0)))
        log.calls.assert().containsExactly("guard:g", "rule:g.0", "guard:p", "rule:p.0")
    }

    @Test
    fun `S6 an explicit deny ends the evaluation without reaching later layers`() {
        val missing = Cause.MissingPolicies(setOf("gone"), IllegalStateException("missing"))
        val policies = PolicySet(
            listOf(
                denyAll(GLOBAL, "g"),
                LayerInput.Loaded(PRINCIPAL, listOf(fixture.policy("p", DENY to R.THROW)), missing),
                LayerInput.Unavailable(ROLE, IllegalStateException("redis")),
            ),
        )
        decide(policies).assert().isEqualTo(Decision.Deny(stmt(GLOBAL, "g", 0)))
        log.calls.assert().containsExactly("guard:g", "rule:g.0")
    }

    @Test
    fun `E1 an allow that throws in a later layer fails the request although an earlier layer allowed`() {
        val throwing = fixture.policy("p", ALLOW to R.THROW)
        val policies = PolicySet(listOf(allowAll(GLOBAL, "g"), loaded(PRINCIPAL, throwing)))
        val expected = Cause.MatcherFailed(
            PRINCIPAL,
            "p",
            stmt(PRINCIPAL, "p", 0),
            (throwing.grants[0].rule as TestRule).error
        )
        decide(policies).assert().isEqualTo(Decision.Indeterminate(expected))
    }

    @Test
    fun `S8 missing policies fail the layer before any of its guards runs`() {
        val error = IllegalStateException("missing")
        val missing = Cause.MissingPolicies(setOf("gone"), error)
        val policies = PolicySet(
            listOf(
                allowAll(GLOBAL, "g"),
                LayerInput.Loaded(PRINCIPAL, listOf(fixture.policy("p", DENY to R.MATCH)), missing),
                loaded(ROLE, fixture.app("app", RoleGrant("admin", "delete", DENY))),
            ),
        )
        decide(policies).assert().isEqualTo(Decision.Indeterminate(missing))
        log.calls.assert().containsExactly("guard:g", "rule:g.0")
    }

    private fun unresolvedError(app: TestGroup, position: Int) = (app.grants[position].rule as UnresolvedGrant).error

    private fun missingPermission(app: TestGroup, role: String, permission: String, position: Int) =
        Decision.Indeterminate(
            Cause.MissingPermissions(
                StatementRef.RolePermission(app.id, role, permission, ""),
                unresolvedError(app, position),
            ),
        )

    @Test
    fun `S10a an unresolved grant fails the role layer where the deny pass reaches it`() {
        // The allow before it is not looked at in the deny pass, and the deny after it is never reached.
        val app = fixture.app(
            "app",
            RoleGrant("viewer", "read", ALLOW),
            RoleGrant("viewer", "write", DENY, R.ACTION_MISS),
            RoleGrant.unresolved("viewer", "gone"),
            RoleGrant("admin", "delete", DENY),
        )
        val policies = PolicySet(listOf(allowAll(GLOBAL, "g"), loaded(ROLE, app)))
        decide(policies).assert().isEqualTo(missingPermission(app, "viewer", "gone", 2))
        log.calls.assert().containsExactly("guard:g", "rule:g.0", "guard:app", "rule:viewer.write")
    }

    @Test
    fun `S10a a deny matched before an unresolved grant decides`() {
        val app = fixture.app(
            "app",
            RoleGrant("admin", "delete", DENY),
            RoleGrant.unresolved("viewer", "gone"),
        )
        decide(PolicySet(listOf(loaded(ROLE, app))))
            .assert().isEqualTo(Decision.Deny(StatementRef.RolePermission("app", "admin", "delete", "admin.delete")))
    }

    @Test
    fun `S10a an unresolved grant is not reached when the app condition misses`() {
        val app = fixture.app("app", RoleGrant.unresolved("viewer", "gone"), guard = G.MISS)
        val policies = PolicySet(listOf(allowAll(GLOBAL, "g"), loaded(ROLE, app)))
        decide(policies).assert().isEqualTo(Decision.Permit(stmt(GLOBAL, "g", 0)))
    }

    @Test
    fun `S10a an unresolved grant is not reached after an earlier layer denies`() {
        val app = fixture.app("app", RoleGrant.unresolved("viewer", "gone"))
        decide(
            PolicySet(listOf(denyAll(GLOBAL, "g"), loaded(ROLE, app)))
        ).assert().isEqualTo(Decision.Deny(stmt(GLOBAL, "g", 0)))
    }

    @Test
    fun `only the role layer has unresolved grants`() {
        val group = TestGroup("p", fixture.guard("p"), listOf(Grant(UnresolvedGrant("gone", IllegalStateException()))))
        assertThrownBy<IllegalStateException> {
            CoSecEngine.evaluate(request(), PolicySet(listOf(loaded(GLOBAL, group))))
        }
    }

    @Test
    fun `an unavailable layer fails the evaluation when it is reached`() {
        val error = IllegalStateException("redis")
        val policies = PolicySet(
            listOf(
                allowAll(GLOBAL, "g"),
                LayerInput.Unavailable(PRINCIPAL, error),
                loaded(ROLE, fixture.app("app", RoleGrant("admin", "delete", DENY)))
            )
        )
        decide(policies).assert().isEqualTo(Decision.Indeterminate(Cause.SourceUnavailable(PRINCIPAL, error)))
        log.calls.assert().containsExactly("guard:g", "rule:g.0")
    }

    @Test
    fun `S14 a guard that throws fails the layer before later guards and any rule`() {
        val throwing = fixture.policy("b", DENY to R.MATCH, guard = G.THROW)
        val policies = PolicySet(
            listOf(
                loaded(GLOBAL, fixture.policy("a", DENY to R.MATCH), throwing, fixture.policy("c", DENY to R.MATCH))
            ),
        )
        val expected = Cause.MatcherFailed(GLOBAL, "b", null, (throwing.guard as TestGuard).error)
        decide(policies).assert().isEqualTo(Decision.Indeterminate(expected))
        log.calls.assert().containsExactly("guard:a", "guard:b")
    }

    @Test
    fun `S14 a rule that throws in the deny pass fails the layer before a later deny matches`() {
        val throwing = fixture.policy("a", ALLOW to R.MATCH, DENY to R.THROW, DENY to R.MATCH)
        val policies = PolicySet(listOf(loaded(GLOBAL, throwing)))
        val expected = Cause.MatcherFailed(
            GLOBAL,
            "a",
            stmt(GLOBAL, "a", 1),
            (throwing.grants[1].rule as TestRule).error
        )
        decide(policies).assert().isEqualTo(Decision.Indeterminate(expected))
        log.calls.assert().containsExactly("guard:a", "rule:a.1")
    }

    @Test
    fun `a rule that throws after the deciding deny is never evaluated`() {
        val policies = PolicySet(
            listOf(loaded(GLOBAL, fixture.policy("a", DENY to R.MATCH, DENY to R.THROW, ALLOW to R.THROW)))
        )
        decide(policies).assert().isEqualTo(Decision.Deny(stmt(GLOBAL, "a", 0)))
        log.calls.assert().containsExactly("guard:a", "rule:a.0")
    }

    @Test
    fun `an allow that throws fails the layer when no deny matched`() {
        val throwing = fixture.policy("a", DENY to R.ACTION_MISS, ALLOW to R.THROW, ALLOW to R.MATCH)
        val policies = PolicySet(listOf(loaded(GLOBAL, throwing)))
        decide(policies).assert().isInstanceOf(Decision.Indeterminate::class.java)
    }

    @Test
    fun `role layer decisions name the app, the granting role and the permission`() {
        val app = fixture.app(
            "app",
            RoleGrant("viewer", "read", ALLOW, R.ACTION_MISS),
            RoleGrant("viewer", "list", ALLOW),
            RoleGrant("admin", "delete", DENY),
        )
        val policies = PolicySet(listOf(allowAll(GLOBAL, "g"), loaded(ROLE, app)))
        decide(
            policies
        ).assert().isEqualTo(Decision.Deny(StatementRef.RolePermission("app", "admin", "delete", "admin.delete")))

        val allowOnly = fixture.app(
            "app",
            RoleGrant("viewer", "read", ALLOW, R.ACTION_MISS),
            RoleGrant("viewer", "list", ALLOW)
        )
        decide(PolicySet(listOf(loaded(ROLE, allowOnly))))
            .assert().isEqualTo(Decision.Permit(StatementRef.RolePermission("app", "viewer", "list", "viewer.list")))
    }

    @Test
    fun `a role layer whose app condition misses has no result`() {
        val app = fixture.app("app", RoleGrant("admin", "delete", DENY), guard = G.MISS)
        decide(PolicySet(listOf(loaded(ROLE, app)))).assert().isEqualTo(Decision.Deny(null))
        log.calls.assert().containsExactly("guard:app")
    }

    @Test
    fun `a role-layer rule without its granting role is a broken adapter, not a decision`() {
        val group = TestGroup("app", fixture.guard("app"), listOf(Grant(fixture.rule("r", DENY, id = "p"), via = null)))
        assertThrownBy<IllegalStateException> {
            CoSecEngine.evaluate(request(), PolicySet(listOf(loaded(ROLE, group))))
        }
    }

    @Test
    fun `an Error from a matcher propagates`() {
        val group = TestGroup(
            "g",
            fixture.guard("g"),
            listOf(
                Grant(object : Rule by fixture.rule("r", DENY) {
                    override fun match(request: AuthzRequest): RuleMatch = throw StackOverflowError()
                })
            ),
        )
        TraceLevel.entries.forEach { level ->
            assertThrownBy<StackOverflowError> {
                CoSecEngine.evaluate(request(), PolicySet(listOf(loaded(GLOBAL, group))), level)
            }
        }
    }

    @Test
    fun `more than 64 groups keep their guard results`() {
        for (size in listOf(63, 64, 65, 128, 129, 640)) {
            // Only the last group's guard matches; the other groups would deny if they were evaluated.
            val groups = (0 until size).map {
                fixture.policy("g$it", DENY to R.MATCH, guard = if (it == size - 1) G.MATCH else G.MISS)
            }
            val lastDenies = PolicySet(listOf(LayerInput.Loaded(GLOBAL, groups)))
            decide(lastDenies).assert().isEqualTo(Decision.Deny(stmt(GLOBAL, "g${size - 1}", 0)))

            val noneMatch = (0 until size).map { fixture.policy("g$it", DENY to R.MATCH, guard = G.MISS) }
            decide(PolicySet(listOf(LayerInput.Loaded(GLOBAL, noneMatch)))).assert().isEqualTo(Decision.Deny(null))
        }
    }

    @Test
    fun `a policy set keeps the layer order`() {
        val global = LayerInput.Absent(GLOBAL, AbsentReason.NO_POLICIES)
        val role = LayerInput.Absent(ROLE, AbsentReason.NO_ROLES)
        PolicySet(listOf(global, role)).layers.assert().containsExactly(global, role)
        assertThrownBy<IllegalArgumentException> { PolicySet(listOf(role, global)) }
        assertThrownBy<IllegalArgumentException> { PolicySet(listOf(global, global)) }
    }
}
