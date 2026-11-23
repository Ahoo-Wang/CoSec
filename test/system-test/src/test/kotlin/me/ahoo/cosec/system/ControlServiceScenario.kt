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

import me.ahoo.cosec.system.ServiceControl.Companion.bindRole
import me.ahoo.cosec.system.ServiceControl.Companion.putPolicy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith

/**
 * What a service that calls the control-plane host directly may do, on the service port (8082), as the principal it
 * states (`CTL-58`, `CTL-57`, `wiki/examples/control-service/`): a principal bound to a read-only policy reads and is
 * refused a write by the action (403); the port serves a tenant's documents and changes and nothing of the platform's,
 * by its document or by a change's id (404); a stated principal proposes, skips the shadow window and publishes; the
 * record of each step says who was stated and which connection carried it, and the peer is never what the caller wrote;
 * and a credential stated as an API key never approves, where the same principal stated as a token does, which is what
 * the network boundary costs (`STA-9`); and a platform administrator stated on the service port is no trust root: its
 * grant waits for a person (`CTL-81`).
 */
@Tag("system")
@Tag("service-path")
@Order(25)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class ControlServiceScenario {
    private val root get() = Control(SignIn.root())

    /** The IAM's principal: bound to `control-service` in the platform document. */
    private val iam get() = ServiceControl(SystemTopology.controlService1, "svc-iam")

    /** A reporting service's: bound to `control-reader`, which holds no write. */
    private val reports get() = ServiceControl(SystemTopology.controlService1, "svc-reports")

    @Test
    @Order(1)
    fun `a service principal bound to a read-only policy reads a tenant document and is refused a write by the action`() {
        root.seed(KEY, "tenants/svc-t.json")
        val head = root.revision(KEY)

        reports.revision(KEY).assert().isEqualTo(head)
        // The policy holds no cosec:write: refused, and nothing is recorded.
        reports.propose(KEY, head, listOf(putPolicy("svc-denied")), "a reader writes").status.assert().isEqualTo(403)
        // A principal nothing binds reads nothing.
        ServiceControl(SystemTopology.controlService1, "svc-stranger").document(KEY).status.assert().isEqualTo(403)
        root.revision(KEY).assert().isEqualTo(head)
    }

    @Test
    @Order(2)
    fun `the service port serves no platform document and no change of one, by its document or by its id`() {
        val head = root.revision("platform")
        val probe = listOf(putPolicy("svc-probe"))
        val id = root.propose("platform", head, probe, "system test: a platform change a service cannot reach")
            .created().id()
        try {
            // A route the profile does not serve is a 404 before the control plane is asked, whoever asks.
            iam.get("/documents/platform").status.assert().isEqualTo(404)
            iam.get("/documents/platform/revisions").status.assert().isEqualTo(404)
            iam.get("/changes/$id").status.assert().isEqualTo(404)
            iam.step(id, "publish").status.assert().isEqualTo(404)
            iam.step(id, "abandon").status.assert().isEqualTo(404)
            // The same principal on the operators' port is a 403, not a 404: what it is not granted, as the policies say.
            val operators = iam.on(SystemTopology.control1)
            operators.document("platform").status.assert().isEqualTo(403)
            operators.document(KEY).status.assert().isEqualTo(200)
            // The change is untouched: its author still reads it.
            root.change(id).ok().json()["state"].assert().isEqualTo("CHECKED")
        } finally {
            root.step(id, "abandon").ok()
        }
    }

    @Test
    @Order(3)
    fun `a stated principal proposes a tenant change, skips its window and publishes, and the record names who connected`() {
        val head = root.revision(KEY)
        // What the caller writes about itself is not what the host records: the peer is the connection's.
        val caller = ServiceControl(
            SystemTopology.controlService1,
            "svc-iam",
            extra = listOf("X-Forwarded-For" to FORGED_PEER, "Forwarded" to "for=$FORGED_PEER"),
        )
        val proposed = caller.propose(
            KEY,
            head,
            listOf(putPolicy("svc-skipped")),
            "a service skips its window"
        ).created().json()
        val id = proposed.getValue("id") as String
        proposed["state"].assert().describedAs("$proposed").isEqualTo("CHECKED")
        caller.step(id, "skip-shadow", mapOf("reason" to "system test: a service's own change"))
            .ok().json()["state"].assert().isEqualTo("READY")
        caller.step(id, "publish").ok().json()["state"].assert().isEqualTo("PUBLISHED")

        root.revision(KEY).assert().isEqualTo(head + 1)
        reports.document(KEY).ok().body.assert().contains("svc-skipped")

        val published = eventually(Bounds.AUDIT_DELIVERY, "the PUBLISHED event of $id", {
            Audit.changeEventsOf(id)
        }) { events ->
            events.any { it["kind"] == "PUBLISHED" }
        }.first { it["kind"] == "PUBLISHED" }
        (published["operator"] as Map<*, *>).let {
            it["principal"].assert().isEqualTo("svc-iam")
            it["tenant"].assert().isEqualTo(StatedIdentity.PLATFORM)
        }
        val origin = published["origin"] as Map<*, *>
        origin["asserted"].assert().describedAs("the host believed the identity it was sent: $origin").isEqualTo(true)
        val via = origin["via"] as Map<*, *>
        via["method"].assert().describedAs("the credential as it was stated: $origin").isEqualTo("api-key")
        via["credential"].assert().isEqualTo("svc-iam")
        (origin["peer"] as String?).assert().describedAs(
            "the connection's address: $origin"
        ).isNotBlank().isNotEqualTo(FORGED_PEER)
    }

    @Test
    @Order(4)
    fun `an api-key never approves and a jwt stated for the same administrator does, which is what the network boundary costs`() {
        val head = root.revision(KEY)
        val grant = listOf(putPolicy("svc-grant"), bindRole("svc-grant", "analyst"))
        val proposed = iam.propose(KEY, head, grant, "a service grants").created().json()
        val id = proposed.getValue("id") as String
        (proposed["escalations"] as List<*>?).orEmpty().assert().describedAs(
            "what the change grants waits for a person: $proposed"
        ).isNotEmpty()
        val waiting = iam.step(id, "skip-shadow", mapOf("reason" to "system test: a grant to approve")).ok().json()
        waiting["state"].assert().describedAs("$waiting").isEqualTo("AWAITING_APPROVAL")
        val approve = mapOf("candidate" to waiting["candidate"])

        // Approving is a person's: it is no route of the service port, and no stated API key is a person.
        iam.step(id, "approve", approve).status.assert().isEqualTo(404)
        val asKey = ServiceControl(SystemTopology.control1, SignIn.ROOT, method = "api-key", jti = "stated-key")
        asKey.step(id, "approve", approve).status.assert().isEqualTo(403)
        iam.change(id).ok().json()["state"].assert().isEqualTo("AWAITING_APPROVAL")

        // The platform administrator, stated as the holder of CoSec's own token: whoever can reach the port can say so.
        val asToken = ServiceControl(SystemTopology.control1, SignIn.ROOT, method = "jwt", jti = "stated-token")
        asToken.step(id, "approve", approve).ok().json()["state"].assert().isEqualTo("READY")
        iam.step(id, "publish").ok().json()["state"].assert().isEqualTo("PUBLISHED")

        val approved = eventually(
            Bounds.AUDIT_DELIVERY,
            "the APPROVED event of $id",
            { Audit.changeEventsOf(id) }
        ) { events ->
            events.any { it["kind"] == "APPROVED" }
        }.first { it["kind"] == "APPROVED" }
        (approved["operator"] as Map<*, *>)["principal"].assert().isEqualTo(SignIn.ROOT)
        val origin = approved["origin"] as Map<*, *>
        origin["asserted"].assert().isEqualTo(true)
        (origin["via"] as Map<*, *>)["method"].assert().describedAs("$origin").isEqualTo("jwt")
        (origin["via"] as Map<*, *>)["credential"].assert().isEqualTo("stated-token")
    }

    @Test
    @Order(5)
    fun `the IAM's own call, with the header CoSec's writer made, is the same call as any service's`() {
        val head = root.revision(KEY)
        val answer = Operator.onboard("svc-t", "svc-onboarded").ok().json()
        answer["state"].assert().describedAs("$answer").isEqualTo("PUBLISHED")
        root.revision(KEY).assert().isEqualTo(head + 1)
        reports.document(KEY).ok().body.assert().contains("svc-onboarded")

        val id = answer.getValue("id") as String
        val published = eventually(Bounds.AUDIT_DELIVERY, "the PUBLISHED event of $id", {
            Audit.changeEventsOf(id)
        }) { events ->
            events.any { it["kind"] == "PUBLISHED" }
        }.first { it["kind"] == "PUBLISHED" }
        (published["operator"] as Map<*, *>)["principal"].assert().isEqualTo("svc-iam")
        val origin = published["origin"] as Map<*, *>
        origin["asserted"].assert().isEqualTo(true)
        // A service's own identity, as CoSec's writer states it: a workload's credential of its own name (IDN-34).
        (origin["via"] as Map<*, *>)["method"].assert().describedAs("$origin").isEqualTo("service")
        (origin["peer"] as String?).assert().describedAs("the IAM's address, not the runner's: $origin").isNotBlank()
    }

    @Test
    @Order(6)
    fun `a platform administrator stated on the service port is no trust root, and its grant waits for a person - CTL-81`() {
        val head = root.revision(KEY)
        // Whoever reaches the service port may state the platform administrator: the change is analyzed all the same.
        val stated = ServiceControl(SystemTopology.controlService1, SignIn.ROOT, method = "jwt", jti = "stated-root")
        val grant = listOf(putPolicy("svc-root-grant"), bindRole("svc-root-grant", "analyst"))
        val proposed = stated.propose(KEY, head, grant, "a stated administrator grants").created().json()
        val id = proposed.getValue("id") as String
        (proposed["escalations"] as List<*>?).orEmpty().assert().describedAs("$proposed").isNotEmpty()
        stated.step(id, "skip-shadow", mapOf("reason" to "system test: a stated administrator"))
            .ok().json()["state"].assert().isEqualTo("AWAITING_APPROVAL")
        root.revision(KEY).assert().isEqualTo(head)
        stated.step(id, "abandon").ok()
    }

    private companion object {
        const val KEY = "tenant:svc-t"

        /** An address no connection of this test has: what a caller may write in a forwarding header. */
        const val FORGED_PEER = "203.0.113.9"
    }
}
