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
import java.util.UUID

/**
 * Scenario 11, one gateway in front of several apps (SCP-11, the app of a route, `wiki/integration/web.md`): the routes
 * under `/shop/` and `/ledger/` of the system's gateways name the apps `shop` and `ledger` (`gateway/application.yaml`), both
 * served by the one service behind them. Each app has its own document (`documents/apps/`) with system roles, and two of the
 * role ids, `manager` and `clerk`, are defined by both with different grants. The tenant `acme` has a tenant document, which
 * binds one more permission of `shop` to `clerk`; the tenant `plain` has none. The routes the other scenarios use name no
 * app and serve every app, so nothing here changes what they see.
 *
 * What it shows: a credential of one app is refused at the route of another, and so is one without an app; a role id two
 * apps define grants what each app's document says, at the route of that app only; an app document's update reaches both
 * gateways within the bound, and each gateway decides with the new revision; a tenant's customisation stays inside its
 * app, and a tenant without a document is held, not read again.
 *
 * What it does not show (scenario 2): that a role held once applies in every app that defines its id. Here the role comes
 * from the token, which is for one app, so each app sees the role list of its own credential. Where memberships come from
 * a provider, which is asked for a tenant and a principal only, a role held once applies in every app that defines the id;
 * the stack has no provider, so the suite makes no claim about that case.
 *
 * Its steps run in order, on documents each step may rely on being installed by the ones before.
 */
@Tag("system")
@Order(35)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(TranscriptExtension::class)
class MultiAppScenario {
    private val root get() = Control(SignIn.root())

    private fun token(principal: String, tenant: String, app: String?, vararg roles: String): SignedIn =
        SignIn.of(principal, tenant, app = app, roles = roles.toList())

    private fun call(method: String, path: String, who: SignedIn, gateway: URI = SystemTopology.gateway): Exchange =
        Http.send(method, gateway.resolve(path), listOf(who.bearer), null)

    private fun everywhere(method: String, path: String, who: SignedIn): List<Probe> =
        SystemTopology.gateways.mapIndexed { index, gateway ->
            Probe("gw-${index + 1} $method $path") { call(method, path, who, gateway) }
        }

    /** Both gateways permit it, within the propagation bound, and keep permitting it. */
    private fun permitted(what: String, method: String, path: String, who: SignedIn) =
        convergeEverywhere(Bounds.SNAPSHOT_PROPAGATION, what, everywhere(method, path, who)) { it.status == 200 }

    /** Both gateways refuse it with the problem [slug]. */
    private fun refused(slug: String, method: String, path: String, who: SignedIn) {
        for (gateway in SystemTopology.gateways) {
            Contract.assertProblem(call(method, path, who, gateway), slug)
        }
    }

    @Test
    @Order(1)
    fun `a credential of one app is refused at the route of another, and so is one without an app`() {
        seeded()
        val shop = token("rae", "multi", "shop", "manager")
        val ledger = token("rae", "multi", "ledger", "manager")
        val none = token("rae", "multi", null, "manager")

        refused("scope-rejected", "GET", "/ledger/entries/1", shop)
        refused("scope-rejected", "GET", "/shop/items/1", ledger)
        refused("scope-rejected", "GET", "/shop/items/1", none)
        refused("scope-rejected", "GET", "/ledger/entries/1", none)

        // Each app's own credential is served: the refusals above are about the route, not a gateway that refuses everything.
        permitted("a manager of shop at the route of shop", "GET", "/shop/items/1", shop)
        permitted("a manager of ledger at the route of ledger", "GET", "/ledger/entries/1", ledger)
    }

    @Test
    @Order(2)
    fun `a role id two apps define grants what each app's document says, at that app's route only`() {
        seeded()
        val shopManager = token("rae", "multi", "shop", "manager")
        val ledgerManager = token("rae", "multi", "ledger", "manager")
        permitted("the manager of shop writes an item", "POST", "/shop/items/1", shopManager)
        permitted("the manager of ledger reads an entry", "GET", "/ledger/entries/1", ledgerManager)
        // The same role id, the same principal, the other app's grant: ledger's manager has no write.
        refused("forbidden", "POST", "/ledger/entries/1", ledgerManager)

        val shopClerk = token("cal", "multi", "shop", "clerk")
        val ledgerClerk = token("cal", "multi", "ledger", "clerk")
        permitted("the clerk of shop reads an item", "GET", "/shop/items/1", shopClerk)
        refused("forbidden", "POST", "/shop/items/1", shopClerk)
        // ledger defines clerk with no permission yet: defined, and granting nothing.
        refused("forbidden", "GET", "/ledger/entries/1", ledgerClerk)
    }

    @Test
    @Order(3)
    fun `an app document's update reaches both gateways within the bound, each decides with its revision, and the other app is unchanged`() {
        val initial = seeded()
        val auditorShop = token("ada", "multi", "shop", "auditor")
        val auditorLedger = token("ada", "multi", "ledger", "auditor")
        val ledgerManager = token("rae", "multi", "ledger", "manager")
        // Revision 1 of shop defines no role auditor: its holder is permitted nothing there, and ledger defines none either.
        refused("forbidden", "GET", "/shop/items/1", auditorShop)
        refused("forbidden", "GET", "/ledger/entries/1", auditorLedger)

        val published = root.seed("app:shop", "apps/shop-2.json")
        published.assert().describedAs(
            "the revision of app:shop after the update"
        ).isGreaterThan(initial.getValue("shop"))

        permitted("the role auditor, added to app:shop at revision $published", "GET", "/shop/items/1", auditorShop)
        // ledger decides as before, throughout: its own manager is served, and the same role id is still nothing there.
        permitted("the manager of ledger, after shop's update", "GET", "/ledger/entries/1", ledgerManager)
        refused("forbidden", "GET", "/ledger/entries/1", auditorLedger)

        // The revision each gateway decided with is in the audit record of the request it decided.
        SystemTopology.gateways.forEachIndexed { index, gateway ->
            assertDecidedWith(
                gateway,
                "gw-${index + 1}",
                "/shop/items/rev-${UUID.randomUUID()}",
                auditorShop,
                published
            )
            assertDecidedWith(
                gateway,
                "gw-${index + 1}",
                "/ledger/entries/rev-${UUID.randomUUID()}",
                ledgerManager,
                initial.getValue("ledger")
            )
        }
    }

    private fun assertDecidedWith(gateway: URI, name: String, path: String, who: SignedIn, revision: Long) {
        call("GET", path, who, gateway).status.assert().describedAs("$name permits $path").isEqualTo(200)
        val record = eventually(
            Bounds.AUDIT_DELIVERY,
            "the audit record of $path",
            { Audit.recordOf(path) }
        ) { it != null }!!
        Audit.appRevision(record).assert()
            .describedAs("the revision of the app document $name decided $path with, in its audit record: $record")
            .isEqualTo(revision)
    }

    @Test
    @Order(5)
    fun `a tenant's customisation stays inside its app, and a tenant without a document is held, not read again`() {
        seeded()
        val plain = token("cleo", "plain", "shop", "clerk")
        val metrics = SystemTopology.gatewayManagement

        // The first request of a tenant that has no document loads its entry; the second finds it held (#1725).
        val before = Metrics.sum(metrics, "cosec_snapshot_loads_total", "kind=\"tenant\"")
        call(
            "GET",
            "/shop/items/1",
            plain
        ).status.assert().describedAs("the first request of the tenant plain").isEqualTo(200)
        val first = Metrics.sum(metrics, "cosec_snapshot_loads_total", "kind=\"tenant\"")
        first.assert()
            .describedAs(
                "tenant loads at gw-1 after the first request of a tenant that has no document (it was $before)"
            )
            .isGreaterThan(before)
        call("GET", "/shop/items/1", plain).status.assert().isEqualTo(200)
        Metrics.sum(metrics, "cosec_snapshot_loads_total", "kind=\"tenant\"").assert()
            .describedAs("tenant loads at gw-1 after the second request of the same tenant: held, not read again")
            .isEqualTo(first)

        val acme = token("cleo", "acme", "shop", "clerk")
        val acmeLedger = token("cleo", "acme", "ledger", "clerk")
        // acme's document binds shipping to clerk, in shop; plain has no document and does not have it.
        permitted("the clerk of acme ships", "POST", "/shop/shipments/1", acme)
        permitted("the clerk of plain reads", "GET", "/shop/items/1", plain)
        refused("forbidden", "POST", "/shop/shipments/1", plain)
        // The customisation adds the one permission, in the one app: no write in shop, nothing in ledger.
        refused("forbidden", "POST", "/shop/items/1", acme)
        refused("forbidden", "GET", "/ledger/entries/1", acmeLedger)
    }

    /** The documents of the scenario, published once for the class, with the revisions they came out at. */
    private fun seeded(): Map<String, Long> = documents

    private companion object {
        val documents: Map<String, Long> by lazy {
            val operator = Control(SignIn.root())
            mapOf(
                "shop" to operator.seed("app:shop", "apps/shop.json"),
                "ledger" to operator.seed("app:ledger", "apps/ledger.json"),
                "acme" to operator.seed("tenant:acme", "tenants/acme.json"),
            )
        }
    }
}
