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
 * Scenario 3, a policy change's whole lifecycle through the control plane (CTL-1, CTL-4, CTL-10, CTL-11, APR-1): a tenant
 * administrator proposes a grant they do not hold themselves; both gateways and the enforcing service sample live traffic
 * against the staged candidate, which never decides; the window closes, a platform administrator approves, the author
 * publishes, and the grant takes effect at every process without a restart; a rollback takes it back. Changes the control
 * plane refuses change nothing. One tenant, `lc`, its steps in order.
 */
@Tag("system")
@Tag("control")
@Order(30)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class PolicyLifecycleScenario {
    private val root get() = Control(SignIn.root())

    /** The tenant's administrator: `cosec:tenant-admin` on `tenant:lc`, through the role `owner` in `cosec-control`. */
    private val owner get() = Control(SignIn.of("tom", TENANT, app = SignIn.CONTROL_APP, roles = listOf("owner")))

    /** The analyst the change is for, as the gateways and the enforcer (the app orders) see them. */
    private val analyst get() = SignIn.of("ann", TENANT, app = "orders", roles = listOf("analyst"))

    private val reports get() = Probe.everywhere("/reports/1", listOf(analyst.bearer))

    @Test
    @Order(1)
    fun `before the change the analyst is refused everywhere, and a refused change changes nothing`() {
        root.seed(KEY, "tenants/lc.json")
        val owner = owner
        val head = owner.revision(KEY)
        reports.forEach { Contract.assertProblem(it.send(), "forbidden") }

        // Someone without cosec:write on the document: refused, nothing recorded.
        val outsider = Control(SignIn.of("ann", TENANT, app = SignIn.CONTROL_APP, roles = listOf("analyst")))
        outsider.propose(KEY, head, grant(), "not mine to write").status.assert().isEqualTo(403)
        // A base that is not the head (CTL-4): a conflict, nothing overwritten.
        owner.propose(KEY, head - 1, grant(), "a stale base").status.assert().isEqualTo(409)
        // A binding of a policy that resolves nowhere (CTL-1): the change stays a draft, and cannot be staged.
        val broken = owner.propose(KEY, head, listOf(binding("ghost")), "a binding of nothing").created().json()
        broken["state"].assert().isEqualTo("DRAFT")
        owner.step(broken.getValue("id") as String, "shadow").status.assert().isBetween(400, 499)

        owner.revision(KEY).assert().isEqualTo(head)
        reports.forEach { Contract.assertProblem(it.send(), "forbidden") }
    }

    @Test
    @Order(2)
    fun `a grant goes through its shadow window, an approval and its publish, then decides at every process`() {
        val owner = owner
        val head = owner.revision(KEY)
        val proposed = owner.propose(KEY, head, grant(), "analysts read reports").created().json()
        val id = proposed.getValue("id") as String
        proposed["state"].assert().isEqualTo("CHECKED")
        // The owner holds no ALLOW of these reports: what the change grants waits for a second person (APR-1).
        (proposed["escalations"] as List<*>?).orEmpty().assert().isNotEmpty()

        owner.step(id, "shadow").ok().json()["state"].assert().isEqualTo("SHADOWING")
        // Live traffic while the window is open: the candidate is compared, never enforced.
        val closed = eventually(Bounds.SHADOW_CLOSE, "the shadow window to close", {
            reports.forEach { probe -> Contract.assertProblem(probe.send(), "forbidden") }
            owner.change(id).ok().json()
        }) { it["state"] != "SHADOWING" }
        closed["state"].assert().describedAs("after the window: $closed").isEqualTo("AWAITING_APPROVAL")
        val shadow = closed.getValue("shadow") as Map<*, *>
        val summary = shadow["closed"] as Map<*, *>
        summary["blocked"].assert().isEqualTo(false)
        summary["insufficient"].assert().isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val counts = summary["counts"] as Map<String, Number>
        counts["WIDENED"]?.toLong().assert().describedAs("counts $counts").isNotNull().isGreaterThan(0L)

        // The author never approves their own change; the platform administrator does, for the candidate it reviewed.
        owner.step(id, "approve", mapOf("candidate" to closed["candidate"])).status.assert().isEqualTo(403)
        root.step(
            id,
            "approve",
            mapOf("candidate" to closed["candidate"])
        ).ok().json()["state"].assert().isEqualTo("READY")
        // A window that saw WIDENED requests publishes only with those numbers acknowledged (CTL-11).
        owner.step(id, "publish").status.assert().isEqualTo(428)
        val ack = mapOf("ack" to mapOf("window" to shadow["window"], "counts" to counts))
        owner.step(id, "publish", ack).ok().json()["state"].assert().isEqualTo("PUBLISHED")

        convergeEverywhere(Bounds.SNAPSHOT_PROPAGATION, "the published grant", reports) { it.status == 200 }
        owner.revision(KEY).assert().isEqualTo(head + 1)
    }

    @Test
    @Order(3)
    fun `a rollback restores the previous decision at every process`() {
        val owner = owner
        val head = owner.revision(KEY)
        val id = owner.rollback(KEY, head - 1, "take the grant back").created().id()
        owner.step(id, "skip-shadow", mapOf("reason" to "system test: a rollback")).ok()
        owner.step(id, "publish").ok().json()["state"].assert().isEqualTo("PUBLISHED")
        convergeEverywhere(Bounds.SNAPSHOT_PROPAGATION, "the rollback", reports) { it.status == 403 }
        reports.forEach { Contract.assertProblem(it.send(), "forbidden") }
    }

    private fun grant(): List<Map<String, Any?>> = listOf(
        mapOf(
            "op" to "putPolicy",
            "policy" to mapOf(
                "id" to "reports",
                "statements" to listOf(
                    mapOf(
                        "name" to "read-reports",
                        "effect" to "allow",
                        "action" to "http:GET",
                        "resource" to listOf("http:path:/behind/reports/**", "http:path:/reports/**"),
                    ),
                ),
            ),
        ),
        binding("reports"),
    )

    private fun binding(policy: String): Map<String, Any?> = mapOf(
        "op" to "putBinding",
        "binding" to mapOf("id" to "analysts-$policy", "subject" to mapOf("role" to "analyst"), "app" to "*", "space" to "*", "policy" to policy),
    )

    private companion object {
        const val TENANT = "lc"
        const val KEY = "tenant:lc"
    }
}
