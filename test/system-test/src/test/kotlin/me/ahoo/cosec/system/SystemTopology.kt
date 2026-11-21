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

import java.net.URI
import java.nio.file.Path

/**
 * Where the stack of `test/system-test/compose.yaml` listens, as the system-test workflow (or whoever runs `systemTest`) says
 * in the environment. Nothing here has a default: a scenario run against the wrong stack would prove nothing.
 */
object SystemTopology {
    /** The first gateway replica, the image under test. */
    val gateway: URI get() = url("COSEC_SYSTEM_GATEWAY_URL")

    /** The second replica of the same image, on the same Redis. */
    val gateway2: URI get() = url("COSEC_SYSTEM_GATEWAY2_URL")

    /** The gateway that refuses a token while revocations cannot be read (`revocation.fail-closed`). */
    val gatewayStrict: URI get() = url("COSEC_SYSTEM_GATEWAY_STRICT_URL")

    /** The first gateway's management port: `/actuator/health` and the scrape endpoint `/actuator/prometheus`. */
    val gatewayManagement: URI get() = url("COSEC_SYSTEM_GATEWAY_MANAGEMENT_URL")

    /** The second replica's management port: the same endpoints as [gatewayManagement], of `gw-2`. */
    val gateway2Management: URI get() = url("COSEC_SYSTEM_GATEWAY2_MANAGEMENT_URL")

    /** Both replicas. */
    val gateways: List<URI> get() = listOf(gateway, gateway2)

    /** The IAM (`test/system-auth`): it issues the tokens and calls the control-plane host's service port, reached directly for its operator routes. */
    val auth: URI get() = url("COSEC_SYSTEM_AUTH_URL")

    /** The control-plane host's replicas, by the operators' API (8080 of the container), the service API (8082) and the management port (8081). */
    val control1: URI get() = url("COSEC_SYSTEM_CONTROL1_URL")
    val control2: URI get() = url("COSEC_SYSTEM_CONTROL2_URL")
    val controlService1: URI get() = url("COSEC_SYSTEM_CONTROL1_SERVICE_URL")
    val controlService2: URI get() = url("COSEC_SYSTEM_CONTROL2_SERVICE_URL")
    val controlManagement1: URI get() = url("COSEC_SYSTEM_CONTROL1_MANAGEMENT_URL")
    val controlManagement2: URI get() = url("COSEC_SYSTEM_CONTROL2_MANAGEMENT_URL")

    /**
     * Whether this is the embedded deployment (leg 2): the IAM carries the control plane and no control host runs, so what
     * the control hosts' ports and replicas are for is not there. `COSEC_SYSTEM_TOPOLOGY=embedded`; otherwise the control host's.
     */
    val embedded: Boolean get() = System.getenv("COSEC_SYSTEM_TOPOLOGY") == "embedded"

    /** The second IAM of the embedded deployment: the same image, its control routes off (`cosec.control.http.enabled=false`). */
    val authSdk: URI get() = url("COSEC_SYSTEM_AUTH_SDK_URL")

    /** The enforcing servlet service (`test/system-enforcer`), the host of the app `orders`. */
    val enforcer: URI get() = url("COSEC_SYSTEM_ENFORCER_URL")

    /** The enforcer's management port, outside CoSec on Spring MVC: its probes. */
    val enforcerManagement: URI get() = url("COSEC_SYSTEM_ENFORCER_MANAGEMENT_URL")

    /** The Redis every process shares, as `cosec-migrate` reaches it from the runner. */
    val redis: String get() = env("COSEC_SYSTEM_REDIS_URL")

    /** The `cosec-migrate` start script built from the commit under test, as an absolute path. */
    val migrate: Path get() = Path.of(env("COSEC_SYSTEM_MIGRATE"))

    /** The gateways' HMAC secret: a throwaway value of the workflow's, never a deployment's. */
    val jwtSecret: String get() = env("COSEC_SYSTEM_JWT_SECRET")

    val contract: Path get() = Path.of(property("cosec.system.contract"))

    val documents: Path get() = Path.of(property("cosec.system.documents"))

    val transcripts: Path get() = Path.of(property("cosec.system.transcripts"))

    private fun url(name: String): URI = URI.create(env(name).trimEnd('/'))

    private fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is not set: the scenarios run against the stack of test/system-test/compose.yaml (test/system-test/README.md)")

    private fun property(name: String): String = System.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is not set: run the scenarios through Gradle (:system-test:systemTest)")
}
