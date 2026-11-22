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

package me.ahoo.cosec.system

import me.ahoo.cosec.system.ServiceControl.Companion.putPolicy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Onboarding a tenant when there are ten thousand of them (RFC 0012): nobody is granted anything per tenant, so nobody
 * approves anything per tenant. The role `tenant-admin` is defined once, in the document of the control plane's app
 * (`wiki/examples/control-tenant-admin/`), approved once by people, and its permission names the member's own tenant. A
 * person is in the role by membership (here the token the IAM issued at login, from its own data), not by a binding in a
 * document of any tenant. The IAM's own part is to create the tenant's document, which grants no one anything.
 *
 * (a) the IAM onboards a tenant with no approval; (b) the tenant's administrator, signed in through the gateway, proposes
 * and publishes a change to its own tenant's document, and is refused another's, by its key and in the listing; (c) a
 * binding written per tenant by a principal of the platform tenant still waits for a person.
 *
 * Both deployments run it: the IAM calls the control plane over the control host's service port in one, through the SDK
 * in process in the other, and the administrator reaches the control API through the gateway in both.
 */
@Tag("system")
@Tag("control")
@Order(28)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class TenantOnboardingScenario {
    private val root get() = Control(SignIn.root())

    /** A member of [tenant] holding the role `tenant-admin` of the control app: the token says so, as the IAM's data would. */
    private fun administrator(tenant: String) =
        Control(SignIn.of("tess", tenant, app = SignIn.CONTROL_APP, roles = listOf("tenant-admin")))

    @Test
    @Order(1)
    fun `the control app's document defines the role once, and a tenant onboarded by the IAM needs no approval`() {
        // The role, once, by people: a platform change to the control app's own document.
        root.seed("app:cosec-control", "apps/cosec-control.json")

        val onboarded = Operator.onboard("tn-one", "tn-one-pages").ok().json()
        onboarded["state"].assert().describedAs("$onboarded").isEqualTo("PUBLISHED")
        (onboarded["escalations"] as List<*>?).orEmpty().assert().describedAs(
            "nothing was granted: $onboarded"
        ).isEmpty()
        root.revision(KEY).assert().isEqualTo(1L)
        Operator.onboard("tn-two", "tn-two-pages").ok().json()["state"].assert().isEqualTo("PUBLISHED")
    }

    @Test
    @Order(2)
    fun `the tenant administrator, by membership, proposes and publishes a change to its own document with no approval`() {
        val admin = administrator("tn-one")
        val head = admin.revision(KEY)
        val proposed = admin.propose(
            KEY,
            head,
            listOf(putPolicy("tn-one-more")),
            "its administrator writes"
        ).created().json()
        val id = proposed.getValue("id") as String
        proposed["state"].assert().describedAs("$proposed").isEqualTo("CHECKED")
        admin.step(id, "skip-shadow", mapOf("reason" to "system test: its own change")).ok()
        admin.step(id, "publish").ok().json()["state"].assert().isEqualTo("PUBLISHED")
        admin.revision(KEY).assert().isEqualTo(head + 1)
    }

    @Test
    @Order(3)
    fun `the tenant administrator is refused another tenant's document, by its key and in the listing`() {
        val admin = administrator("tn-one")
        // The role names the member's own tenant, so no policy grants another's; and the control plane checks, before any
        // policy is read, that a tenant's principal acts on its own tenant's document only (TNT-3, TenantIsolation): two
        // lines, and a refusal is the same 403 whichever holds.
        admin.document(OTHER).status.assert().isEqualTo(403)
        admin.propose(
            OTHER,
            1,
            listOf(putPolicy("tn-one-trespass")),
            "another tenant's document"
        ).status.assert().isEqualTo(403)
        // The listing shows a tenant's principal its own document and nothing else.
        val listed = admin.documents("tenant").ok().json()["documents"] as List<*>
        listed.map { (it as Map<*, *>)["key"] }.assert().describedAs("the listing: $listed").containsExactly(KEY)
        // The platform's is nobody's but the platform tenant's.
        admin.document("platform").status.assert().isEqualTo(403)
    }

    @Test
    @Order(4)
    fun `a binding written for one tenant by a principal of the platform tenant still waits for a person`() {
        val waiting = Operator.grant("tn-one", "tn-one-readers", "analyst").ok().json()
        waiting["state"].assert().describedAs("$waiting").isEqualTo("AWAITING_APPROVAL")
        (waiting["escalations"] as List<*>?).orEmpty().assert().describedAs("$waiting").isNotEmpty()
        // The tenant's administrator may not approve a grant that is the platform's (the role holds no cosec:approve).
        administrator(
            "tn-one"
        ).step(waiting.getValue("id") as String, "approve", mapOf("candidate" to waiting["candidate"]))
            .status.assert().isEqualTo(403)
        // It holds the document's slot while it waits: given up, so the changes after it stage.
        root.step(waiting.getValue("id") as String, "abandon").ok()
    }

    @Test
    @Order(5)
    fun `the IAM checks a change before storing anything, and the check stores nothing - CTL-78`() {
        val head = root.revision(KEY)
        val checked = Operator.check("tn-one", "tn-one-nowhere", "analyst").ok().json()
        checked["state"].assert().describedAs("$checked").isEqualTo("DRAFT")
        (checked["findings"] as List<*>).assert().describedAs("$checked").isNotEmpty()
        // Nothing stored: the document is where it was.
        root.revision(KEY).assert().isEqualTo(head)
    }

    @Test
    @Order(6)
    fun `the IAM projects a custom role of a business app into a tenant with no person, by the role class - CTL-79`() {
        root.seed("app:projected", "apps/projected.json")
        val head = root.revision(KEY)
        val projected = Operator.projectRole("tn-one", "auditors", "projected", "reports.read").ok().json()
        projected["state"].assert().describedAs("$projected").isEqualTo("PUBLISHED")
        // The record names the class it was granted by, and nothing that waited for a person.
        reasons(projected).assert().describedAs("$projected").containsExactly("ROLE_CLASS")
        root.revision(KEY).assert().isEqualTo(head + 1)
        root.document(KEY).ok().body.assert().contains("auditors")
    }

    @Test
    @Order(7)
    fun `a custom role of the control plane's own app, by the IAM, waits for a person - CTL-79`() {
        val head = root.revision(KEY)
        val waiting = Operator.projectRole(
            "tn-one",
            "governors",
            "cosec-control",
            "tenant-document.administer"
        ).ok().json()
        waiting["state"].assert().describedAs("$waiting").isEqualTo("AWAITING_APPROVAL")
        reasons(waiting).assert().describedAs("$waiting").isNotEmpty().doesNotContain("ROLE_CLASS")
        root.revision(KEY).assert().isEqualTo(head)
        root.step(waiting.getValue("id") as String, "abandon").ok()
    }

    @Test
    @Order(8)
    fun `a person's change of the tenant's roles is analyzed as before, never by the role class - CTL-79`() {
        val admin = administrator("tn-one")
        val head = admin.revision(KEY)
        val role = mapOf(
            "op" to "putRole",
            "role" to mapOf("id" to "readers", "app" to "projected", "permissions" to listOf("reports.read")),
        )
        val proposed = admin.propose(KEY, head, listOf(role), "a person writes a role").created().json()
        reasons(proposed).assert().describedAs("$proposed").doesNotContain("ROLE_CLASS")
    }

    private fun reasons(change: Map<*, *>): List<Any?> =
        (change["escalations"] as List<*>?).orEmpty().map { (it as Map<*, *>)["reason"] }

    private companion object {
        const val KEY = "tenant:tn-one"
        const val OTHER = "tenant:tn-two"
    }
}
