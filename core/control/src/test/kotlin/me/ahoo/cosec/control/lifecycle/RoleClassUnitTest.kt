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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Escalation.Reason
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleDefinition
import me.ahoo.cosec.document.RolePermissions
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/** `CTL-79`: what the role class lifts, of which changes, and when it asks whether the author holds the grant. */
class RoleClassUnitTest {
    private val acme = SnapshotKey.Tenant("acme")
    private val author = Actor(TenantId.PLATFORM, PrincipalId("svc-iam"))
    private val putRole = Operation.PutRole(
        RoleDefinition(RoleId("night"), RolePermissions.Listed(listOf("read")), app = AppId("orders"))
    )

    private fun escalations(vararg reasons: Reason) = reasons.map { Escalation("f-$it", it) }

    private val control = AppId("cosec-control")

    /** A head holding the role night of orders and the role governors of the control plane's app. */
    private val base = PolicyDocument(
        kind = DocumentKind.TENANT,
        owner = "acme",
        revision = 1,
        publishedAt = Instant.EPOCH,
        policies = emptyList(),
        bindings = emptyList(),
        roles = listOf(
            RoleDefinition(RoleId("night"), RolePermissions.Listed(listOf("read")), app = AppId("orders")),
            RoleDefinition(RoleId("governors"), RolePermissions.Listed(listOf("govern")), app = control),
        ),
    )

    private fun covers(key: SnapshotKey, operations: List<Operation>) = RoleClass.covers(key, operations, base, control)

    private fun apply(key: SnapshotKey, operations: List<Operation>, escalations: List<Escalation>): Pair<List<Escalation>, Boolean> {
        var asked = false
        val answer = runBlocking {
            RoleClass.apply(
                escalations,
                RoleClass.Proposed(key, operations, base, control),
                author
            ) { true.also { asked = true } }
        }
        return answer to asked
    }

    @Test
    fun `it lifts every doubt about who grants, into one record that names the holder and what it lifted - CTL-79`() {
        val (lifted, asked) = apply(
            acme,
            listOf(putRole),
            escalations(Reason.CROSS_TENANT, Reason.NOT_COVERED, Reason.DENY_OVERLAP)
        )
        asked.assert().isTrue()
        lifted.single().reason.assert().isEqualTo(Reason.ROLE_CLASS)
        lifted.single().informational.assert().isTrue()
        lifted.single().detail.assert().contains("svc-iam@(platform)").contains("CROSS_TENANT").contains("DENY_OVERLAP")
    }

    @Test
    fun `what the analysis could not tell still waits, whoever holds what - CTL-79`() {
        listOf(
            Reason.UNREADABLE,
            Reason.UNRESOLVED,
            Reason.TOO_LARGE,
            Reason.ANONYMOUS,
            Reason.NOT_ANALYZED
        ).forEach { reason ->
            val held = escalations(Reason.CROSS_TENANT, reason)
            val (answer, asked) = apply(acme, listOf(putRole), held)
            answer.assert().describedAs("$reason").isEqualTo(held)
            asked.assert().isFalse()
        }
    }

    @Test
    fun `only a tenant's document, and only roles - CTL-79`() {
        val held = escalations(Reason.CROSS_TENANT)
        listOf(SnapshotKey.App("orders"), SnapshotKey.Platform).forEach { key ->
            covers(key, listOf(putRole)).assert().isFalse()
            apply(key, listOf(putRole), held).first.assert().isEqualTo(held)
        }
        covers(acme, emptyList()).assert().isFalse()
        covers(acme, listOf(putRole, Operation.RemoveRole(RoleId("night")))).assert().isTrue()
        val mixed = listOf(putRole, Operation.RemovePolicy(me.ahoo.cosec.model.PolicyId("p")))
        covers(acme, mixed).assert().isFalse()
        apply(acme, mixed, held).first.assert().isEqualTo(held)
        // Nothing to lift: nothing recorded, and the grant never asked about.
        apply(acme, listOf(putRole), emptyList()).assert().isEqualTo(emptyList<Escalation>() to false)
    }

    @Test
    fun `a role with no app is never the class's - CTL-79`() {
        val held = escalations(Reason.CROSS_TENANT)
        val appless = listOf(Operation.PutRole(RoleDefinition(RoleId("loose"), RolePermissions.Listed(listOf("read")))))
        covers(acme, appless).assert().isFalse()
        apply(acme, appless, held).assert().isEqualTo(held to false)
        // Over a role of an app the head holds, too.
        val over = listOf(Operation.PutRole(RoleDefinition(RoleId("night"), RolePermissions.Listed(listOf("read")))))
        covers(acme, over).assert().isFalse()
    }

    @Test
    fun `a role of the control plane's own app is never the class's, put or removed - CTL-79`() {
        val held = escalations(Reason.CROSS_TENANT)
        val governors = Operation.PutRole(
            RoleDefinition(RoleId("governors"), RolePermissions.Listed(listOf("govern")), app = control),
        )
        listOf(
            listOf(governors),
            listOf(putRole, governors),
            listOf(Operation.RemoveRole(RoleId("governors"))),
            // A role the head does not hold: its app is not known, so it is not the class's.
            listOf(Operation.RemoveRole(RoleId("unknown"))),
            // A role of another app written over the control plane's role of that id removes the control plane's.
            listOf(
                Operation.PutRole(
                    RoleDefinition(RoleId("governors"), RolePermissions.Listed(listOf("read")), app = AppId("orders")),
                ),
            ),
        ).forEach { operations ->
            covers(acme, operations).assert().describedAs("$operations").isFalse()
            apply(acme, operations, held).assert().isEqualTo(held to false)
        }
    }
}
