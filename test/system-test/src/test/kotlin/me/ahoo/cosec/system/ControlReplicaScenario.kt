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
import java.io.IOException
import java.net.URI

/**
 * Two replicas of the control-plane host on one store (`CTL-58`, `CTL-10`, RFC 0007 §5.7): a change is proposed on one,
 * its shadow window started on the other and published on the first, with every replica reading the same changes; and a
 * window still closes, and its change publishes, when the replica that started it is stopped: whichever replica holds the
 * lease of the maintenance pass closes the windows, and the service port of a stopped replica refuses connections at once.
 * It runs before the scenarios that stop or restart a data-plane instance: a window waits for every live instance, and an
 * instance that was stopped is waited for until its registration lapses.
 */
@Tag("system")
@Tag("service-path")
@Order(27)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class ControlReplicaScenario {
    private val root get() = Control(SignIn.root())
    private val first get() = ServiceControl(SystemTopology.controlService1, "svc-iam")
    private val second get() = ServiceControl(SystemTopology.controlService2, "svc-iam")

    /** A principal of the tenant: its requests are what the shadow window compares. */
    private val traffic by lazy {
        val member = SignIn.of("rita", TENANT, app = "orders")
        Probe.everywhere("/svc-r/1", listOf(member.bearer))
    }

    @Test
    @Order(1)
    fun `a change proposed on one replica is shadowed on the other and published on the first`() {
        root.seed(KEY, "tenants/svc-r.json")
        convergeEverywhere(Bounds.SNAPSHOT_PROPAGATION, "the tenant's own pages", traffic) { it.status == 200 }
        val head = root.revision(KEY)

        val id = first.propose(KEY, head, listOf(putPolicy("svc-replica-a")), "proposed on one replica").created().id()
        // The other replica reads the same change: a change is the store's, not a replica's.
        eventually(
            Bounds.SNAPSHOT_PROPAGATION,
            "the change on the other replica",
            { second.change(id) }
        ) { it.status == 200 }
        second.step(id, "shadow").ok().json()["state"].assert().isEqualTo("SHADOWING")

        val closed = eventually(Bounds.SHADOW_CLOSE, "the window to close", {
            traffic.forEach { it.send() }
            first.change(id).ok().json()
        }) { it["state"] != "SHADOWING" }
        closed["state"].assert().describedAs("after the window: $closed").isEqualTo("READY")
        first.step(id, "publish").ok().json()["state"].assert().isEqualTo("PUBLISHED")
        eventually(Bounds.SNAPSHOT_PROPAGATION, "the published head on the other replica", {
            second.revision(KEY)
        }) { it == head + 1 }
    }

    @Test
    @Order(2)
    fun `a window still closes and publishes when the replica that started it is stopped`() {
        val head = second.revision(KEY)
        val id = first.propose(
            KEY,
            head,
            listOf(putPolicy("svc-replica-b")),
            "its replica is stopped mid-window"
        ).created().id()
        first.step(id, "shadow").ok().json()["state"].assert().isEqualTo("SHADOWING")

        Compose.stop("control-1")
        try {
            // The listener is closed with the replica: a connection is refused, not left open.
            refusesConnections(SystemTopology.controlService1).assert().describedAs("control-1's service port").isTrue()
            refusesConnections(SystemTopology.control1).assert().describedAs("control-1's operators' port").isTrue()

            val closed = eventually(Bounds.SHADOW_CLOSE_AFTER_FAILOVER, "the window to close without control-1", {
                traffic.forEach { it.send() }
                second.change(id).ok().json()
            }) { it["state"] != "SHADOWING" }
            closed["state"].assert().describedAs("after the window: $closed").isEqualTo("READY")
            second.step(id, "publish").ok().json()["state"].assert().isEqualTo("PUBLISHED")
            second.revision(KEY).assert().isEqualTo(head + 1)
        } finally {
            // Whatever happened above, the next scenarios need control-1 back: the gateways route to it.
            Compose.start("control-1")
            eventually(Bounds.REPLICA_START, "control-1 to turn ready again", { readiness() }) { it == 200 }
        }
        // It reads what was published while it was down.
        eventually(
            Bounds.SNAPSHOT_PROPAGATION,
            "the head on the restarted replica",
            { first.revision(KEY) }
        ) { it == head + 1 }
    }

    /** The status of control-1's readiness probe; 0 while the starting server resets the connection (the poll asks again). */
    private fun readiness(): Int = try {
        Http.get(SystemTopology.controlManagement1.resolve("/actuator/health/readiness")).status
    } catch (@Suppress("SwallowedException") e: IOException) {
        0
    }

    private fun refusesConnections(base: URI): Boolean = try {
        Http.get(base.resolve("/cosec/control/v1/documents/platform"))
        false
    } catch (@Suppress("SwallowedException") e: IOException) {
        true
    }

    private companion object {
        const val TENANT = "svc-r"
        const val KEY = "tenant:svc-r"
    }
}
