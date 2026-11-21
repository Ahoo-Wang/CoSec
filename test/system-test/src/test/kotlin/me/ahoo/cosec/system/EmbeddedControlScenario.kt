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

/**
 * What only the embedded deployment has (`CTL-65`, `CTL-57`, `CTL-69`): the IAM carries the control plane, so its own
 * calls are the SDK's, in process, and the record says so; and an IAM that embeds the control plane with its control routes
 * off (`cosec.control.http.enabled=false`) serves none, still calls the SDK, and is a second instance of the control plane
 * over the same store: what it publishes, the instance that serves the control API reads.
 */
@Tag("system")
@Tag("embedded")
@Order(29)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class EmbeddedControlScenario {
    private val root get() = Control(SignIn.root())

    @Test
    @Order(1)
    fun `the IAM's own call is the SDK's, in process, and its record says it was not carried by a connection`() {
        root.seed("app:cosec-control", "apps/cosec-control.json")
        val published = Operator.onboard("em-one", "em-one-pages").ok().json()
        published["state"].assert().describedAs("$published").isEqualTo("PUBLISHED")

        val id = published.getValue("id") as String
        val event = eventually(
            Bounds.AUDIT_DELIVERY,
            "the PUBLISHED event of $id",
            { Audit.changeEventsOf(id) }
        ) { events ->
            events.any { it["kind"] == "PUBLISHED" }
        }.first { it["kind"] == "PUBLISHED" }
        (event["operator"] as Map<*, *>)["principal"].assert().isEqualTo("svc-iam")
        val origin = event["origin"] as Map<*, *>
        (origin["via"] as Map<*, *>)["method"].assert().describedAs(
            "how the step was made: $origin"
        ).isEqualTo("in-process")
        origin["peer"].assert().describedAs("no connection carried it: $origin").isNull()
    }

    @Test
    @Order(2)
    fun `with the control routes off the IAM serves none and still calls the SDK`() {
        val stated = "CoSec-Identity" to StatedIdentity.header(SignIn.ROOT, method = "jwt")
        // Nothing of the control API answers on this instance: the pipeline permits the path, and no route is there.
        Http.get(
            SystemTopology.authSdk.resolve("/cosec/control/v1/documents/platform"),
            stated
        ).status.assert().isEqualTo(404)
        // Its own call, with no route in front of it, is the SDK's: the tenant's document is created and published.
        val onboarded = Http.post(
            SystemTopology.authSdk.resolve("/admin/onboard"),
            mapOf("tenant" to "em-two", "policy" to "em-two-pages"),
            stated,
        ).ok().json()
        onboarded["state"].assert().describedAs("$onboarded").isEqualTo("PUBLISHED")
        // The instance that serves the control API reads what the other one published.
        eventually(Bounds.SNAPSHOT_PROPAGATION, "the tenant on the instance that serves the control API", {
            root.document("tenant:em-two")
        }) { it.status == 200 }
    }
}
