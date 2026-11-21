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
import java.time.Duration
import java.time.Instant

/**
 * The control-plane host as a process (`STA-9`, `STA-10`, `CTL-58`): its three ports each serve what they are for, a
 * header it cannot trust is refused and never read as someone else, and without the statement of what can reach it the
 * host does not start.
 */
@Tag("system")
@Tag("service-path")
@Order(26)
@ExtendWith(TranscriptExtension::class)
class ControlHostScenario {
    private val platformAdministrator get() = ServiceControl(
        SystemTopology.control1,
        SignIn.ROOT,
        method = "jwt",
        jti = "stated-token"
    )

    @Test
    fun `the operators' port, the service port and the management port each serve what they are for`() {
        // The operators' API reads the platform document for the administrator it is told is calling.
        platformAdministrator.document("platform").ok()
        // The service port is the narrow profile: the same call is not served there, and nothing but the control API is.
        platformAdministrator.on(SystemTopology.controlService1).document("platform").status.assert().isEqualTo(404)
        Http.get(
            SystemTopology.controlService1.resolve("/actuator/health"),
            "CoSec-Identity" to StatedIdentity.header(SignIn.ROOT, method = "jwt"),
        ).status.assert().isEqualTo(404)
        // No actuator on an application port: the probes and the scrape endpoint are on the management port, outside CoSec.
        Http.get(
            SystemTopology.control1.resolve("/actuator/prometheus"),
            "CoSec-Identity" to StatedIdentity.header(SignIn.ROOT, method = "jwt"),
        ).status.assert().isNotEqualTo(200)
        for (management in listOf(SystemTopology.controlManagement1, SystemTopology.controlManagement2)) {
            Http.get(management.resolve("/actuator/health/liveness")).status.assert().isEqualTo(200)
            Http.get(management.resolve("/actuator/health/readiness")).status.assert().isEqualTo(200)
            Http.get(management.resolve("/actuator/prometheus")).status.assert().isEqualTo(200)
            // ...and the control API is not served on it.
            Http.get(
                management.resolve("/cosec/control/v1/documents/platform"),
                "CoSec-Identity" to StatedIdentity.header(SignIn.ROOT, method = "jwt"),
            ).status.assert().isEqualTo(404)
        }
        // Both replicas serve both application ports.
        for ((operators, service) in listOf(
            SystemTopology.control1 to SystemTopology.controlService1,
            SystemTopology.control2 to SystemTopology.controlService2,
        )) {
            ServiceControl(operators, SignIn.ROOT, method = "jwt").document("platform").ok()
            ServiceControl(service, "svc-reports").document("tenant:svc-t").ok()
        }
    }

    @Test
    fun `an identity the host cannot trust is refused, be it none, doubled, expired or too old`() {
        val uri = SystemTopology.control1.resolve("/cosec/control/v1/documents/platform")
        val fresh = StatedIdentity.header(SignIn.ROOT, method = "jwt")
        // None: no principal stands behind the request.
        Http.get(uri).status.assert().isEqualTo(401)
        // Two headers: which one would be believed? Neither (400, never the first or the last).
        Contract.assertProblem(
            Http.send("GET", uri, listOf("CoSec-Identity" to fresh, "CoSec-Identity" to fresh), null),
            "invalid-request",
        )
        // A credential that expired a long time ago, past any clock skew.
        val expired = StatedIdentity.header(
            SignIn.ROOT,
            method = "jwt",
            credentialExpiresAt = Instant.now().minus(Duration.ofMinutes(10)),
        )
        Contract.assertProblem(Http.get(uri, "CoSec-Identity" to expired), "token-expired")
        // An identity made long ago: replaying a captured header is limited to the maximum age (60 s by default).
        val old = StatedIdentity.header(
            SignIn.ROOT,
            method = "jwt",
            issuedAt = Instant.now().minus(Duration.ofMinutes(5))
        )
        Contract.assertProblem(Http.get(uri, "CoSec-Identity" to old), "token-expired")
        // The same call with a fresh header is served: it was the header that was refused.
        Http.get(uri, "CoSec-Identity" to fresh).status.assert().isEqualTo(200)
    }

    @Test
    fun `a host that is not told what can reach it does not start, and says what to write`() {
        val (exit, output) = Compose.runOnce(
            "control-1",
            "system-control-without-statement",
            mapOf("COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH" to "false"),
        )
        exit.assert().describedAs("the host started without the statement: $output").isNotEqualTo(0)
        output.assert().contains(
            "only-trusted-callers-can-reach"
        ).contains("COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH")
    }
}
