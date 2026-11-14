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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.Catalog
import me.ahoo.cosec.runtime.document.CatalogGroup
import me.ahoo.cosec.runtime.document.CatalogPermission
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.RoleDefinition
import me.ahoo.cosec.runtime.document.RolePermissions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Removing a DENY of the platform or of an app needs a second person (RFC 0007 §7.4.4 as amended on 2026-11-09,
 * `CTL-31`): unlike a tenant's, such a DENY has no document above it that still holds. Removing an ALLOW only narrows.
 */
class DenyRemovalTest {
    private val harness = ChangeHarness()
    private val ids = AtomicInteger()
    private val wms = SnapshotKey.App("wms")

    /** A platform principal who writes, publishes and skips windows, but does not hold `cosec:approve`. */
    private val notTrustRoot = ControlAuthorizer { _, action, _ ->
        if (action == ControlActions.APPROVE) ControlDecision.DENY else ControlDecision.PERMIT
    }

    private fun changes(authorizer: ControlAuthorizer, fourEyes: Boolean = false) = Changes(
        harness.memory,
        harness.revisions,
        { ChangeId("m2-${ids.incrementAndGet()}") },
        authorizer,
        harness.clock,
        ChangeOptions(fourEyes = fourEyes, shadow = IMMEDIATE_WINDOW),
    )

    private fun deny(id: String, action: String = "order:export") = PolicyDefinition(
        PolicyId(id),
        attachable = false,
        condition = null,
        statements = listOf(StatementSource("", Effect.DENY, listOf(action))),
    )

    private fun everyone(id: String, policy: String) =
        Binding(BindingId(id), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId(policy))

    private suspend fun seed(key: SnapshotKey, vararg operations: Operation) {
        val change = harness.changes.propose(PLATO, key, head(key), operations.toList(), "seed")
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        harness.changes.skipShadow(PLATO, change.id, "seed")
        harness.changes.publish(PLATO, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    private suspend fun head(key: SnapshotKey) = harness.revisions.head(key)?.revision ?: 0

    private suspend fun Changes.escalated(key: SnapshotKey, vararg operations: Operation): List<String> {
        val change = propose(PLATO, key, head(key), operations.toList(), "m2")
        change.state.assert().describedAs("${change.findings}").isEqualTo(ChangeState.CHECKED)
        return change.escalations.map { "${it.subject} ${it.reason}" }
    }

    private suspend fun platform() = seed(
        SnapshotKey.Platform,
        Operation.PutPolicy(deny("no-export")),
        Operation.PutPolicy(deny("spare")),
        Operation.PutPolicy(allow("read")),
        Operation.PutBinding(everyone("everyone", "no-export")),
        Operation.PutBinding(everyone("readers", "read")),
    )

    @Test
    fun `removing a platform DENY needs a second person, and removing an ALLOW does not`() = runBlocking<Unit> {
        platform()
        val changes = changes(notTrustRoot)
        changes.escalated(SnapshotKey.Platform, Operation.RemoveBinding(BindingId("everyone")))
            .assert().containsExactly("binding everyone of platform NOT_ANALYZED")
        changes.escalated(SnapshotKey.Platform, Operation.RemovePolicy(PolicyId("spare")))
            .assert().containsExactly("policy spare of platform NOT_ANALYZED")
        changes.escalated(SnapshotKey.Platform, Operation.RemoveBinding(BindingId("readers"))).assert().isEmpty()
        // The review's scenario: skipping the window does not publish it; it waits for a second person.
        val change = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            head(SnapshotKey.Platform),
            listOf(Operation.RemoveBinding(BindingId("everyone"))),
            "m2"
        )
        changes.skipShadow(PLATO, change.id, "m2").state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        assertThrows<ChangeRejectedException> { changes.publish(PLATO, change.id) }
        harness.revisions.head(
            SnapshotKey.Platform
        )!!.document.bindings.map { it.id.value }.assert().contains("everyone")
    }

    @Test
    fun `the trust root removes a DENY alone, and under four-eyes it needs a second person too`() = runBlocking<Unit> {
        platform()
        val removing = Operation.RemoveBinding(BindingId("everyone"))
        changes(PERMIT_ALL).escalated(SnapshotKey.Platform, removing).assert().isEmpty()
        changes(PERMIT_ALL, fourEyes = true).escalated(SnapshotKey.Platform, removing)
            .assert().containsExactly("binding everyone of platform NOT_ANALYZED")
    }

    @Test
    fun `an app role whose permission holds a DENY is removed or narrowed by two people`() = runBlocking<Unit> {
        val catalog = Catalog(
            listOf(
                CatalogGroup(
                    "ops",
                    permissions = listOf(
                        CatalogPermission("read", actions = listOf("wms:read")),
                        CatalogPermission("no-ship"),
                    )
                )
            )
        )
        seed(
            wms,
            Operation.PutCatalog(catalog),
            Operation.PutPolicy(deny("perm:wms/no-ship", "wms:ship")),
            Operation.PutRole(RoleDefinition(RoleId("guard"), RolePermissions.Listed(listOf("read", "no-ship")))),
            Operation.PutRole(RoleDefinition(RoleId("reader"), RolePermissions.Listed(listOf("read")))),
        )
        val changes = changes(notTrustRoot)
        changes.escalated(wms, Operation.RemoveRole(RoleId("guard")))
            .assert().containsExactly("binding role:guard@wms:no-ship of app:wms NOT_ANALYZED")
        changes.escalated(wms, Operation.RemoveRole(RoleId("reader"))).assert().isEmpty()
        // A put needs a second person anyway; the approver also sees which DENY it drops.
        changes.escalated(
            wms,
            Operation.PutRole(RoleDefinition(RoleId("guard"), RolePermissions.Listed(listOf("read"))))
        )
            .assert().containsExactly(
                "document app:wms NOT_ANALYZED",
                "binding role:guard@wms:no-ship of app:wms NOT_ANALYZED",
            )
    }

    @Test
    fun `a tenant removes its own DENY alone`() = runBlocking<Unit> {
        seed(ACME, Operation.PutPolicy(deny("no-export")), Operation.PutBinding(bind("guard", "no-export")))
        val change = changes(
            notTrustRoot
        ).propose(ALICE_OP, ACME, head(ACME), listOf(Operation.RemoveBinding(BindingId("guard"))), "m2")
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        change.escalations.assert().isEmpty()
    }

    @Test
    fun `revoking a break-glass binding ends an emergency, and a policy that cannot be read holds a DENY`() = runBlocking<Unit> {
        val glass = Binding(
            BindingId("glass"),
            SubjectRef.Anyone,
            BindingScope(Match.Any, Match.Any),
            PolicyId("no-export"),
            expiresAt = 1,
            breakGlass = true,
        )
        val base = PolicyDocument(
            DocumentKind.PLATFORM,
            "",
            1,
            Instant.EPOCH,
            listOf(deny("no-export")),
            listOf(glass, everyone("elsewhere", "unknown"), everyone("read", "read")),
        )
        val candidate = PolicyDocument(
            DocumentKind.PLATFORM,
            "",
            1,
            Instant.EPOCH,
            listOf(deny("no-export")),
            emptyList()
        )
        Narrowing.removedDenies(base, candidate) { policy -> allow(policy.value).takeIf { policy.value == "read" } }
            .assert().containsExactly("binding elsewhere")
    }
}
