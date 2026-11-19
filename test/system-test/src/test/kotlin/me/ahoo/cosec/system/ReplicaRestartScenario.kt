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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException

/**
 * Scenario 8, a replica restarted under traffic (ALG-1, SNP-2, PIP-2, PIP-4): while `gw-2` is restarted, requests that must
 * be refused go to both replicas without a pause ([RefusedTraffic]). Every 2xx on one of them fails the scenario, whatever
 * the cause, and the response that was wrong is in the transcript. Connection errors and `503` while the replica starts are
 * expected. Afterwards the replica turns ready and answers as the other does, and a document published then reaches both.
 */
@Tag("system")
@Order(80)
@ExtendWith(TranscriptExtension::class)
class ReplicaRestartScenario {
    private val signedIn = Token.issue("rene", "restart", app = "orders")

    @Test
    fun `no request that must be refused is permitted while a replica restarts, and it comes back deciding as the other does`() {
        val traffic = RefusedTraffic(SystemTopology.gateways, SystemTopology.gateway, signedIn)
        try {
            restartUnderTraffic(traffic)
        } finally {
            traffic.stop()
        }
        traffic.wrong.assert().describedAs(
            "requests that must be refused and were permitted: ${traffic.wrong}"
        ).isEmpty()
        traffic.permits.get().assert().describedAs("the permitted request at gw-1 answered meanwhile").isGreaterThan(0)
        decidesLikeTheOther()
        publishedAfterwards()
    }

    private fun restartUnderTraffic(traffic: RefusedTraffic) {
        eventually(Bounds.SNAPSHOT_PROPAGATION, "traffic flowing", { traffic.refusals.get() }) { it >= 10 }
        Compose.restart("gw-2")
        eventually(Bounds.REPLICA_START, "gw-2 ready again", { readiness() }) { it == 200 }
        val atReady = traffic.refusals.get()
        // Count-based, not timed: the traffic goes on until 100 more refusals have been answered, gw-2's among them.
        eventually(
            Bounds.SNAPSHOT_PROPAGATION,
            "traffic after the restart",
            { traffic.refusals.get() }
        ) { it >= atReady + 100 }
    }

    private fun decidesLikeTheOther() {
        for (gateway in SystemTopology.gateways) {
            Http.get(gateway.resolve("/behind/orders/1"), signedIn.bearer).status.assert().isEqualTo(200)
            Contract.assertProblem(Http.get(gateway.resolve("/behind/denied/1"), signedIn.bearer), "forbidden")
        }
    }

    private fun publishedAfterwards() {
        Control(SignIn.root()).seed("tenant:restarted", "tenants/restarted.json")
        val caller = Token.issue("rene", "restarted", app = "orders")
        convergeEverywhere(
            Bounds.SNAPSHOT_PROPAGATION,
            "the document published after the restart",
            Probe.everywhere("/restarted/1", listOf(caller.bearer)),
        ) { it.status == 200 }
    }

    private fun readiness(): Int = try {
        Http.get(SystemTopology.gateway2.resolve("/readyz")).status
    } catch (_: IOException) {
        0
    }
}
