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
 * Scenario 0, the cold start, on the empty Redis of a fresh stack: until the platform document is published, the gateway
 * answers every routed request `503` and refuses readiness while it stays live; once `cosec-migrate` publishes it, which
 * is the documented way (the control plane cannot write the document that authorizes it), readiness turns and requests
 * are decided. It runs first: every other scenario needs the platform document.
 */
@Tag("system")
@Order(0)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class ColdStartScenario {
    private val gateway get() = SystemTopology.gateway

    @Test
    @Order(1)
    fun `with no documents every routed request is unavailable and readiness refuses, while the gateway stays live`() {
        Contract.assertProblem(Http.get(gateway.resolve("/behind/orders/1")), "unavailable")
        Contract.assertProblem(
            Http.get(gateway.resolve("/behind/orders/1"), Token.issue("alice", "acme").bearer),
            "unavailable"
        )
        Http.get(readiness).status.assert().isEqualTo(503)
        Http.get(liveness).status.assert().isEqualTo(200)
    }

    @Test
    @Order(2)
    fun `once cosec-migrate publishes the platform document, readiness turns and requests are decided`() {
        Migrate.publish("platform.json", "platform")
        eventually(
            Bounds.SNAPSHOT_PROPAGATION,
            "readiness of the gateway",
            { Http.get(readiness) }
        ) { it.status == 200 }
        Contract.assertProblem(Http.get(gateway.resolve("/behind/orders/1")), "unauthorized")
        Http.get(
            gateway.resolve("/behind/orders/1"),
            Token.issue("alice", "acme").bearer
        ).status.assert().isEqualTo(200)
    }

    private val readiness: URI get() = gateway.resolve("/actuator/health/readiness")
    private val liveness: URI get() = gateway.resolve("/actuator/health/liveness")
}
