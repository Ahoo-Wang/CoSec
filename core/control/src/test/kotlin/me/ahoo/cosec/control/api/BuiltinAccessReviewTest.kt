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
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeHarness
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * #1401: an access review resolves the built-in control-plane policies (RFC 0007 §7.3) from the engine's own
 * statements, as enforcement does, rather than counting their bindings opaque. The tenant ones are conditional (their
 * `{t}` is the caller's own tenant); the platform administrator's is not, here bound to a role of any tenant.
 */
class BuiltinAccessReviewTest {
    private val harness = ChangeHarness()
    private val revisions = harness.revisions
    private val plane = DefaultControlPlane(
        harness.changes,
        revisions,
        ChangeAuthority(PERMIT_ALL, ChangeOptions(shadow = IMMEDIATE_WINDOW)),
        Simulator(),
    )
    private val acme = SnapshotKey.Tenant("acme")

    private fun publish(key: SnapshotKey, vararg bindings: Binding) = runBlocking {
        val (kind, owner) = Revisions.ownerOf(key)
        val document = PolicyDocument(kind, owner, 1, StoreFixtures.START, emptyList(), bindings.toList())
        revisions.publish(key, revisions.head(key), document, StoreFixtures.publication())
    }

    private fun control(id: String, subject: SubjectRef, policy: PolicyId) = Binding(
        BindingId(id),
        subject,
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId(""))),
        policy,
    )

    private fun review(verb: String): AccessReport = runBlocking {
        plane.analyze(
            PLATO,
            AccessQuery(
                TenantId("acme"),
                PipelineControlAuthorizer.CONTROL_APP.value,
                "",
                Action("cosec", verb),
                ControlResources.document(acme),
            ),
        )
    }

    private fun install() {
        publish(
            SnapshotKey.Platform,
            control(
                "ops",
                SubjectRef.Role(null, RoleId("ops")),
                BuiltinPolicies.PLATFORM_ADMIN
            ),
        )
        publish(
            acme,
            control("admins", SubjectRef.Role(TenantId("acme"), RoleId("admin")), BuiltinPolicies.TENANT_ADMIN),
            control("viewers", SubjectRef.Role(TenantId("acme"), RoleId("viewer")), BuiltinPolicies.TENANT_VIEWER),
        )
    }

    @Test
    fun `who may read a tenant's document lists its administrators and viewers, and none is opaque`() {
        install()
        val report = review("read")
        report.opaque.assert().isEqualTo(0)
        report.hidden.assert().isEqualTo(0)
        report.allows.associate { it.binding to it.policy }.assert().isEqualTo(
            mapOf(
                "ops" to "cosec:platform-admin",
                "admins" to "cosec:tenant-admin",
                "viewers" to "cosec:tenant-viewer",
            ),
        )
        report.allows.associate { it.binding to it.conditional }.assert()
            .isEqualTo(mapOf("ops" to false, "admins" to true, "viewers" to true))
    }

    @Test
    fun `an action only the administrator holds leaves the viewers out`() {
        install()
        val report = review("publish")
        report.opaque.assert().isEqualTo(0)
        report.allows.map { it.binding }.assert().containsExactlyInAnyOrder("ops", "admins")
    }

    @Test
    fun `a policy no document defines and no built-in is stays opaque`() {
        publish(acme, control("ghost", SubjectRef.Anyone, PolicyId("cosec:nobody")))
        val report = review("read")
        report.allows.assert().isEmpty()
        report.opaque.assert().isEqualTo(1)
    }
}
