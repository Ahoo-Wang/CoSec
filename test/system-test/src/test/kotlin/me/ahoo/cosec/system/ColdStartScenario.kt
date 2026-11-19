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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI

/**
 * Scenario 0, the cold start, on the empty Redis of a fresh stack: until the platform document is published, both
 * gateways, the login and control-plane host and the enforcing service answer every request `503` and refuse readiness,
 * while they stay live; once `cosec-migrate` publishes it, which is the documented way (the control plane cannot write
 * the document that authorizes it), and names the first administrator, every one turns ready and decides. It runs first:
 * every other scenario needs the platform document.
 */
@Tag("system")
@Order(0)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class ColdStartScenario {
    /** Each process with the request it is asked first, and its readiness and liveness. */
    private class Process(
        val name: String,
        val request: URI,
        val readiness: URI,
        val liveness: URI?,
        val app: String? = null
    )

    private val processes: List<Process>
        get() = SystemTopology.gateways.mapIndexed { index, gateway ->
            Process("gw-${index + 1}", gateway.resolve("/behind/orders/1"), gateway.resolve(GATEWAY_READINESS), gateway.resolve(GATEWAY_LIVENESS))
        } + listOf(
            // A WebFlux application's probes pass CoSec like any request: no liveness to ask before the document.
            Process("auth", SystemTopology.auth.resolve("/cosec/control/v1/documents/platform"), SystemTopology.auth.resolve(READINESS), null),
            Process(
                "enforcer",
                SystemTopology.enforcer.resolve("/orders/1"),
                SystemTopology.enforcerManagement.resolve(READINESS),
                SystemTopology.enforcerManagement.resolve(LIVENESS),
                // The host of the app orders: a token of no app is refused for its scope before any document is read.
                app = "orders",
            ),
        )

    @Test
    @Order(1)
    fun `with no documents every request is unavailable and readiness refuses, while each process stays live`() {
        for (process in processes) {
            Transcript.note("process ${process.name}")
            Contract.assertProblem(Http.get(process.request), "unavailable")
            Contract.assertProblem(
                Http.get(process.request, Token.issue("alice", "acme", app = process.app).bearer),
                "unavailable"
            )
            Http.get(process.readiness).status.assert().describedAs("readiness of ${process.name}").isEqualTo(503)
            process.liveness?.let {
                Http.get(
                    it
                ).status.assert().describedAs("liveness of ${process.name}").isEqualTo(200)
            }
        }
    }

    @Test
    @Order(2)
    fun `once cosec-migrate publishes the platform document, every process turns ready and decides`() {
        Migrate.publish("platform.json", "platform")
        Migrate.bootstrap(SignIn.ROOT)
        for (process in processes) {
            eventually(
                Bounds.SNAPSHOT_PROPAGATION,
                "readiness of ${process.name}",
                { Http.get(process.readiness) }
            ) { it.status == 200 }
        }
        for (gateway in SystemTopology.gateways) {
            Contract.assertProblem(Http.get(gateway.resolve("/behind/orders/1")), "unauthorized")
            Http.get(
                gateway.resolve("/behind/orders/1"),
                Token.issue("alice", "acme").bearer
            ).status.assert().isEqualTo(200)
        }
        Contract.assertProblem(Http.get(SystemTopology.enforcer.resolve("/orders/1")), "unauthorized")
        // The first administrator reaches the control plane with a token CoSec's own login issued.
        Control(SignIn.root()).revision("platform").assert().isEqualTo(1L)
    }

    private companion object {
        const val READINESS = "/actuator/health/readiness"
        const val LIVENESS = "/actuator/health/liveness"

        // The gateway image serves its probe groups on its own port as these (its actuator is on a management port).
        const val GATEWAY_READINESS = "/readyz"
        const val GATEWAY_LIVENESS = "/livez"
    }
}
