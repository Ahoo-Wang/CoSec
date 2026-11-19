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
import java.util.UUID

/**
 * Scenario 7, a Redis outage and its recovery (SNP-5, PIP-7, PIP-2, IDN-14, IDN-19): `docker compose stop redis` under a
 * running stack, then `start`, with no restart of any CoSec process. What CoSec documents (`operations/storage.md`, "When
 * Redis is unavailable"), as the deployed processes answer it:
 * - documents already installed keep deciding, and a tenant never read is `503`, never permitted;
 * - a tenant listed in `fail-closed.tenants` is `503` once its document is older than `max-lag` (20 s here); another
 *   tenant is never checked, however old (SNP-5);
 * - a token is accepted while its revocations cannot be read, the documented exception, and `503` on the gateway that has
 *   `revocation.fail-closed` on (PIP-7); a refresh and a logout are `503` and end nothing (IDN-14, IDN-19);
 * - after the restart every process decides again, and a change published then reaches the replicas.
 *
 * A token the scenario needs during the outage is signed before it (a login needs Redis), and each assertion about
 * revocations uses a token no process has verified: a verified one keeps its local "not revoked" for 30 s (IDN-26).
 * Runs after the other scenarios: the stack has been through everything else when it loses its store.
 */
@Tag("system")
@Order(70)
@ExtendWith(TranscriptExtension::class)
class RedisOutageScenario {
    private val root by lazy { Control(SignIn.root()) }

    private fun fresh(tenant: String) = Token.issue("outage-user", tenant, app = "orders")

    private fun page(gateway: java.net.URI, tenant: String) = Http.get(
        gateway.resolve("/behind/$tenant/1"),
        fresh(tenant).bearer
    )

    private val cold = "cold-${UUID.randomUUID().toString().take(8)}"

    private val session by lazy { SignIn.of("ola", "outage", app = "orders") }

    @Test
    fun `documents keep deciding, a tenant failing closed stops, and everything recovers without a restart`() {
        installTheDocuments()
        Compose.stop("redis")
        withinTheLag()
        pastTheLag()
        Compose.start("redis")
        recovered()
        publishedAfterwards()
    }

    private fun installTheDocuments() {
        root.seed("tenant:outage", "tenants/outage.json")
        root.seed("tenant:lagging", "tenants/lagging.json")
        session
        // Every gateway has installed both documents before the outage.
        for (gateway in SystemTopology.gateways + SystemTopology.gatewayStrict) {
            for (tenant in listOf("outage", "lagging")) {
                eventually(
                    Bounds.SNAPSHOT_PROPAGATION,
                    "$tenant's document at $gateway",
                    { page(gateway, tenant) }
                ) { it.status == 200 }
            }
        }
    }

    private fun withinTheLag() {
        // Within the lag: what is installed keeps deciding, a deny stays a deny, a tenant never read is unavailable.
        for (gateway in SystemTopology.gateways) {
            page(gateway, "outage").status.assert().describedAs("outage tenant at $gateway").isEqualTo(200)
            page(
                gateway,
                "lagging"
            ).status.assert().describedAs("lagging tenant, within its lag, at $gateway").isEqualTo(200)
            Contract.assertProblem(Http.get(gateway.resolve("/behind/denied/1"), fresh("outage").bearer), "forbidden")
            Contract.assertProblem(page(gateway, cold), "unavailable")
        }
        // The documented exception, and the setting that closes it (PIP-7).
        Contract.assertProblem(page(SystemTopology.gatewayStrict, "outage"), "unavailable")
        // A refresh and a logout cannot reach the sessions: unavailable, and they end nothing (IDN-14, IDN-19).
        Contract.assertProblem(SignIn.refresh(session), "unavailable")
        Contract.assertProblem(SignIn.logout(session), "unavailable")
    }

    private fun pastTheLag() {
        // Past its lag the tenant that fails closed is refused; the other is never checked (SNP-5).
        eventually(
            Bounds.FAIL_CLOSED_LAG,
            "the failing-closed tenant refused",
            { page(SystemTopology.gateway, "lagging") }
        ) {
            it.status == 503
        }
        Contract.assertProblem(page(SystemTopology.gateway, "lagging"), "unavailable")
        Contract.assertProblem(page(SystemTopology.gateway2, "lagging"), "unavailable")
        for (gateway in SystemTopology.gateways) {
            page(
                gateway,
                "outage"
            ).status.assert().describedAs("outage tenant, long past the lag, at $gateway").isEqualTo(200)
        }
    }

    private fun recovered() {
        // No restart: every process decides and verifies again.
        eventually(
            Bounds.REDIS_RECOVERY,
            "the failing-closed tenant decided again at gw-1",
            { page(SystemTopology.gateway, "lagging") }
        ) {
            it.status == 200
        }
        for (gateway in SystemTopology.gateways + SystemTopology.gatewayStrict) {
            eventually(
                Bounds.REDIS_RECOVERY,
                "tenant lagging at $gateway",
                { page(gateway, "lagging") }
            ) { it.status == 200 }
            eventually(
                Bounds.REDIS_RECOVERY,
                "a tenant never read at $gateway",
                { page(gateway, cold) }
            ) { it.status == 403 }
        }
        eventually(
            Bounds.REDIS_RECOVERY,
            "the enforcer",
            {
                Http.get(
                    SystemTopology.enforcer.resolve("/orders/1"),
                    Token.issue("o", "outage", app = "orders").bearer
                )
            }
        ) {
            it.status == 200
        }
        // The refresh that was unavailable ended nothing: the same pair refreshes, and signs out.
        val refreshed =
            eventually(
                Bounds.REDIS_RECOVERY,
                "the login host refreshing",
                { SignIn.refresh(session) }
            ) { it.status == 200 }
        SignIn.logout(SignIn.pairOf(refreshed)).status.assert().isEqualTo(204)
    }

    private fun publishedAfterwards() {
        // A change published after the outage reaches both replicas and the enforcer.
        eventually(
            Bounds.REDIS_RECOVERY,
            "the control plane reading",
            { root.document("platform") }
        ) { it.status == 200 }
        root.seed("tenant:after", "tenants/after.json")
        val caller = fresh("after")
        convergeEverywhere(
            Bounds.SNAPSHOT_PROPAGATION,
            "the document published after the outage",
            Probe.everywhere("/after/1", listOf(caller.bearer))
        ) {
            it.status == 200
        }
    }
}
