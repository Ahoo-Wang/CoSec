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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ACME
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ChangeHarness
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.NO_MEMBERSHIPS
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.change.operatorFor
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * UC-6: a candidate that would keep its operator out of the control plane is found, by the engine, on the operator's
 * own request: the control API's path through the host's documents, and `cosec:publish` on the document.
 */
class ControlLockoutTest {
    private val harness = ChangeHarness()
    private val engine = EngineAuthority(harness.revisions)
    private val lockout = ControlLockout(harness.revisions)

    private val controlApi = allow("control-api", action = "http:*", resource = "http:path:/cosec/control/v1/**")
    private val admin = allow("admin", action = "cosec:*")
    private val anyone = anyone("control-api")
    private val walt = Binding(
        BindingId("walt"),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("walt")),
        BindingScope(Match.Any, Match.Any),
        PolicyId("admin"),
    )

    private fun anyone(policy: String) = Binding(
        BindingId(policy),
        SubjectRef.Anyone,
        BindingScope(Match.Any, Match.Any),
        PolicyId(policy)
    )

    private fun platform(policies: List<PolicyDefinition>, bindings: List<Binding>) =
        StoreFixtures.candidate(SnapshotKey.Platform, policies, bindings)

    private suspend fun published() {
        val by = operatorFor(SnapshotKey.Platform)
        val operations = listOf(controlApi, admin).map {
            Operation.PutPolicy(it)
        } + listOf(anyone, walt).map { Operation.PutBinding(it) }
        val change = harness.changes.propose(by, SnapshotKey.Platform, 0, operations, "base")
        harness.changes.skipShadow(by, change.id, "test")
        harness.changes.publish(by, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    private val operator get() = engine.operator(TenantId.PLATFORM, "walt")

    @Test
    fun `a candidate that keeps both ways in is no lock-out`() = runBlocking<Unit> {
        published()
        val wider = platform(listOf(controlApi, admin, allow("more", action = "order:read")), listOf(anyone, walt))
        lockout.check(operator, SnapshotKey.Platform, wider).assert().isNull()
    }

    @Test
    fun `a platform that no longer opens the control API's path locks its publisher out`() = runBlocking<Unit> {
        published()
        lockout.check(operator, SnapshotKey.Platform, platform(listOf(admin), listOf(walt)))!!.reason.assert()
            .contains("the request in progress (http:POST)").contains("denied: no statement permits it")
    }

    @Test
    fun `a platform that no longer grants cosec publish locks its publisher out`() = runBlocking<Unit> {
        published()
        lockout.check(
            operator,
            SnapshotKey.Platform,
            platform(listOf(controlApi, admin), listOf(anyone))
        )!!.reason.assert()
            .contains("cosec:publish on [platform]").contains("denied")
    }

    @Test
    fun `a deny in the candidate is named`() = runBlocking<Unit> {
        published()
        val freeze = PolicyDefinition(
            PolicyId("freeze"),
            attachable = false,
            condition = null,
            statements = listOf(StatementSource("", Effect.DENY, listOf("cosec:publish"))),
        )
        val frozen = platform(listOf(controlApi, admin, freeze), listOf(anyone, walt, anyone("freeze")))
        lockout.check(
            operator,
            SnapshotKey.Platform,
            frozen
        ).let {
            it!!.reason.assert().contains("denied by policy freeze, statement 0")
            // The statement's policy is the platform's: an operator who may not read it is told the refusal alone.
            it.namedIn.assert().containsExactly(SnapshotKey.Platform)
            it.unnamed.assert().contains("cosec:publish on [platform] would be denied").doesNotContain("freeze")
        }
    }

    /**
     * DR1-F-13 (`CTL-51`): a refusal by a tenant's platform boundary names that boundary, a policy of the platform
     * document, only to a publisher who may read the platform document, as `simulate` hides any reference but a statement
     * of a readable policy. The lock-out check decides for platform principals, whose tenant names no boundary, so the
     * engine does not answer one here today; the rule is held where the refusal is worded.
     */
    @Test
    fun `a refusal by the platform boundary names it only to a reader of the platform document`() {
        val refusal = ControlLockout.denied(
            "cosec:publish on [platform]",
            StatementRef.Boundary(PolicyId("fence"), TenantId("acme")),
            emptyMap(),
            PolicyHomes(HeldDocuments(null, null, null)),
        )
        refusal.reason.assert().contains("platform boundary fence")
        refusal.namedIn.assert().containsExactly(SnapshotKey.Platform)
        refusal.unnamed.assert().contains("would be denied by the tenant's platform boundary").doesNotContain("fence")
    }

    /**
     * DR1-F-13 (`CTL-51`): where each kind of reference is defined, as `Redaction.visible` reads it: a bound policy's
     * own document (the app's, the tenant's), an attachment's tenant and platform together, a built-in's platform; a
     * policy no document of the view defines, a scope refused and no statement at all name nothing.
     */
    @Test
    fun `each reference is named only where its policy is defined`() {
        val app = SnapshotKey.App("order")
        val tenant = SnapshotKey.Tenant("acme")
        val heads = mapOf(
            SnapshotKey.Platform to Held(platform(listOf(admin), emptyList()), null),
            app to Held(StoreFixtures.candidate(app, listOf(allow("perm:order/read"))), null),
            tenant to Held(StoreFixtures.candidate(tenant, listOf(allow("own"))), null),
        )
        val homes = PolicyHomes(HeldDocuments(heads[SnapshotKey.Platform], heads[app], heads[tenant]))
        fun denied(by: StatementRef?) = ControlLockout.denied("x", by, heads, homes)
        fun bound(policy: String, document: DocumentKind) =
            StatementRef.Policy(PolicyId(policy), 0, "", BindingRef.Bound(BindingId("b"), document))

        denied(bound("perm:order/read", DocumentKind.TENANT)).namedIn.assert().containsExactly(app)
        denied(bound("own", DocumentKind.TENANT)).namedIn.assert().containsExactly(tenant)
        denied(bound("admin", DocumentKind.TENANT)).namedIn.assert().containsExactly(SnapshotKey.Platform)
        denied(StatementRef.Policy(PolicyId("own"), 0, "", BindingRef.Attached(PolicyId("own")))).namedIn
            .assert().containsExactly(tenant, SnapshotKey.Platform)
        denied(StatementRef.Policy(PolicyId("cosec:blacklist"), 0, "", BindingRef.Builtin)).namedIn
            .assert().containsExactly(SnapshotKey.Platform)
        denied(bound("nowhere", DocumentKind.TENANT)).let {
            it.namedIn.assert().isEmpty()
            it.reason.assert().isEqualTo("x would be denied")
        }
        denied(StatementRef.ScopeRejected(ScopeResolution.Rejected.Reason.APP_MISMATCH)).let {
            it.namedIn.assert().isEmpty()
            it.reason.assert().contains("x would be denied (")
        }
        denied(null).reason.assert().isEqualTo("x would be denied: no statement permits it")
    }

    /**
     * DR1-F-13 (`CTL-51`): the lock-out finding names a statement only to a publisher who may read the document its policy
     * is defined in, as `simulate` shows one; to another it says the refusal without the statement.
     */
    @Test
    fun `a statement is named only to a publisher who may read the document that defines it`() = runBlocking<Unit> {
        val named = LockoutCheck { _, _, _ ->
            LockoutCheck.Lockout(
                "x would be denied by policy secret, statement 0",
                listOf(SnapshotKey.Platform),
                "x would be denied"
            )
        }
        val ids = java.util.concurrent.atomic.AtomicInteger()
        suspend fun warning(readsPlatform: Boolean): String {
            val authorizer = ControlAuthorizer { _, action, resource ->
                val platform = ControlResources.document(SnapshotKey.Platform)
                val platformRead = action == ControlActions.READ && resource.type == platform.type && resource.id == platform.id
                if (platformRead && !readsPlatform) ControlDecision.DENY else ControlDecision.PERMIT
            }
            val changes = Changes(
                harness.memory,
                harness.revisions,
                { ChangeId("named-${ids.incrementAndGet()}") },
                authorizer,
                harness.clock,
                ChangeOptions(shadow = IMMEDIATE_WINDOW),
                revocations = RevocationReader.NONE,
                lockout = named,
            )
            val change = changes.propose(operatorFor(ACME), ACME, 0, listOf(Operation.PutPolicy(allow("p"))), "test")
            return change.findings.single { it.check == LockoutCheck.CHECK }.message
        }
        warning(readsPlatform = true).assert().contains("x would be denied by policy secret, statement 0")
        warning(readsPlatform = false).assert().contains("x would be denied.").doesNotContain("secret")
    }

    @Test
    fun `only the documents that gate the control plane are checked, and only for a permitted request`() = runBlocking<Unit> {
        published()
        lockout.check(operator, ACME, StoreFixtures.candidate(ACME)).assert().isNull()
        val noRequest = Operator(Actor(TenantId.PLATFORM, PrincipalId("walt")), NO_MEMBERSHIPS)
        lockout.check(noRequest, SnapshotKey.Platform, platform(emptyList(), emptyList())).assert().isNull()
        // The platform tenant's document is covered: it binds the platform's operators.
        val platformTenant = SnapshotKey.Tenant(TenantId.PLATFORM.value)
        lockout.check(operator, platformTenant, StoreFixtures.candidate(platformTenant)).assert().isNull()
        // The control app's own document is covered too; a credential that does not declare the app cannot be asked in it.
        ControlLockout(harness.revisions, controlApp = AppId("ops"))
            .check(operator, SnapshotKey.App("ops"), StoreFixtures.candidate(SnapshotKey.App("ops")))
            ?.reason.assert().contains("app ops")
    }

    @Test
    fun `a lock-out is a warning at proposal and publishes only acknowledged`() = runBlocking<Unit> {
        published()
        val ids = java.util.concurrent.atomic.AtomicInteger()
        val changes = Changes(
            harness.memory,
            harness.revisions,
            { ChangeId("lock-${ids.incrementAndGet()}") },
            PERMIT_ALL,
            harness.clock,
            ChangeOptions(shadow = IMMEDIATE_WINDOW),
            revocations = RevocationReader.NONE,
            lockout = lockout,
        )
        val closed = platform(listOf(admin), listOf(walt)).copy(revision = 1)
        val change = changes.apply(operator, SnapshotKey.Platform, closed, ApplyMode.REPLACE, "close the control API")
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        val warning = change.findings.single { it.check == LockoutCheck.CHECK }
        warning.severity.assert().isEqualTo(Severity.WARNING)
        warning.message.assert().contains(
            "walt@(platform) could not reach the control plane"
        ).contains("cosec-migrate restore")
        changes.skipShadow(operator, change.id, "test")
        val refused = try {
            changes.publish(operator, change.id)
            throw AssertionError("a lock-out published unacknowledged")
        } catch (e: ChangeRejectedException) {
            e
        }
        refused.status.assert().isEqualTo(422)
        refused.message.assert().contains("\"lockout\": true").contains("Recovering from a lock-out")
        harness.revisions.head(SnapshotKey.Platform)!!.revision.assert().isEqualTo(1)
        val published = changes.publish(operator, change.id, lockout = true)
        published.state.assert().isEqualTo(ChangeState.PUBLISHED)
        published.findings.single { it.check == LockoutCheck.CHECK }.message.assert().contains(
            "acknowledged by walt@(platform)"
        )
    }
}
