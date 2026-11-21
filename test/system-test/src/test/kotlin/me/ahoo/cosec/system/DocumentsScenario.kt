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

/**
 * Scenario 2, documents decide across processes: tenant documents the control plane writes to Redis, read by both
 * gateways and the enforcing service, on tokens the login host issued. Not the engine's rules again (EnginePropertyTest,
 * ScopeResolverTest prove those): one representative of each, where the wiring between the processes is the point.
 */
@Tag("system")
@Tag("control")
@Order(20)
@ExtendWith(TranscriptExtension::class)
class DocumentsScenario {
    private val root by lazy { Control(SignIn.root()) }

    @Test
    fun `a platform DENY wins over a tenant ALLOW of the same request, at every process (ALG-1)`() {
        root.seed("tenant:deny-first", "tenants/deny-first.json")
        val caller = SignIn.of("dora", "deny-first", app = "orders")
        // The tenant's document is installed once its own pages are permitted everywhere.
        convergeEverywhere(
            Bounds.SNAPSHOT_PROPAGATION,
            "the tenant's own pages",
            Probe.everywhere("/deny-first/1", listOf(caller.bearer))
        ) {
            it.status == 200
        }
        for (probe in Probe.everywhere("/denied/1", listOf(caller.bearer))) {
            Contract.assertProblem(probe.send(), "forbidden")
        }
    }

    @Test
    fun `a tenant's document decides for its own principals only (TNT-1)`() {
        root.seed("tenant:iso-a", "tenants/iso-a.json")
        root.seed("tenant:iso-b", "tenants/iso-b.json")
        val a = SignIn.of("ivy", "iso-a", app = "orders", roles = listOf("reader"))
        val b = SignIn.of("ivo", "iso-b", app = "orders", roles = listOf("reader"))
        convergeEverywhere(
            Bounds.SNAPSHOT_PROPAGATION,
            "tenant A's reader on A's pages",
            Probe.everywhere("/isolation/a/1", listOf(a.bearer))
        ) {
            it.status == 200
        }
        convergeEverywhere(
            Bounds.SNAPSHOT_PROPAGATION,
            "tenant B's reader on B's pages",
            Probe.everywhere("/isolation/b/1", listOf(b.bearer))
        ) {
            it.status == 200
        }
        for (probe in Probe.everywhere("/isolation/b/1", listOf(a.bearer)) + Probe.everywhere("/isolation/a/1", listOf(b.bearer))) {
            Contract.assertProblem(probe.send(), "forbidden")
        }
    }

    @Test
    fun `a host with an app serves only tokens of that app, a gateway without one serves every app (SCP-11)`() {
        root.seed("tenant:apps", "tenants/apps.json")
        val orders = SignIn.of("ian", "apps", app = "orders", roles = listOf("reader"))
        val billing = SignIn.of("ian", "apps", app = "billing", roles = listOf("reader"))
        val none = SignIn.of("ian", "apps", roles = listOf("reader"))
        convergeEverywhere(
            Bounds.SNAPSHOT_PROPAGATION,
            "a reader of the app orders",
            Probe.everywhere("/apps/1", listOf(orders.bearer))
        ) {
            it.status == 200
        }
        for (token in listOf(billing, none)) {
            Contract.assertProblem(Http.get(SystemTopology.enforcer.resolve("/apps/1"), token.bearer), "scope-rejected")
            for (gateway in SystemTopology.gateways) {
                Http.get(gateway.resolve("/behind/apps/1"), token.bearer).status.assert().isEqualTo(200)
            }
        }
    }

    @Test
    fun `a role held in a space permits there only, and the service sees that space (SCP-1)`() {
        root.seed("tenant:spaces", "tenants/spaces.json")
        val editor = SignIn.of("eve", "spaces", app = "orders", roles = listOf("editor@s1"))
        val inS1 = listOf(editor.bearer, "CoSec-Space-Id" to "s1")
        convergeEverywhere(
            Bounds.SNAPSHOT_PROPAGATION,
            "the editor in space s1",
            Probe.everywhere("/spaces/1", inS1)
        ) { it.status == 200 }
        for (gateway in SystemTopology.gateways) {
            val security = Http.send(
                "GET",
                gateway.resolve("/behind/spaces/1"),
                inS1,
                null
            ).json()["security"] as Map<*, *>
            security["space"].assert().isEqualTo("s1")
            security["roles"].assert().isEqualTo(listOf("editor"))
        }
        for (probe in Probe.everywhere("/spaces/1", listOf(editor.bearer, "CoSec-Space-Id" to "s2"))) {
            Contract.assertProblem(probe.send(), "scope-rejected")
        }
        for (probe in Probe.everywhere("/spaces/1", listOf(editor.bearer))) {
            Contract.assertProblem(probe.send(), "forbidden")
        }
    }
}
