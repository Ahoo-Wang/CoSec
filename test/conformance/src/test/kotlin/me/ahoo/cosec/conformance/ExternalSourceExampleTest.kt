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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.conformance

import com.example.sync.RoleSync
import com.example.sync.SourceRole
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.local.LocalControlService
import me.ahoo.cosec.control.sdk.Catalog
import me.ahoo.cosec.control.sdk.CatalogGroup
import me.ahoo.cosec.control.sdk.CatalogPermission
import me.ahoo.cosec.control.sdk.ChangeState
import me.ahoo.cosec.control.sdk.ControlClient
import me.ahoo.cosec.control.sdk.EscalationReason
import me.ahoo.cosec.control.sdk.Resource
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.testkit.ScenarioJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The sample of the external-source page (`external-source/kotlin/RoleSync.kt`, compiled with these tests and opting in
 * to no internal API), run against a control plane over the page's documents: a role written or deleted publishes with
 * no person, by the role class and as a removal; the catalog waits for one; a dry run stores nothing (`CTL-79`, `CTL-81`).
 */
class ExternalSourceExampleTest {
    private val example: Path =
        Path.of(System.getProperty("cosec.repository.root"), "wiki", "examples", "external-source")
    private val clock = MutableClock(Instant.parse("2026-11-21T00:00:00Z"))
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val ids = AtomicInteger()

    private fun sync(): RoleSync = runBlocking {
        val documents = JsonNodeFactory.instance.objectNode()
        documents.set("platform", ScenarioJson.read(example.resolve("documents/platform.json")))
        documents.putObject("apps").set("wms", ScenarioJson.read(example.resolve("documents/apps/wms.json")))
        documents.putObject("tenants").set("acme", ScenarioJson.read(example.resolve("documents/tenants/acme.json")))
        ChangeRunner.bootstrap(revisions, documents)
        val engine = ControlEngine(revisions)
        val changes = Changes(
            store,
            revisions,
            { ChangeId("c${ids.incrementAndGet()}") },
            engine.authorizer,
            clock,
            ChangeOptions(),
            revocations = RevocationReader.NONE,
            lockout = LockoutCheck.UNCHECKED,
        )
        val plane = DefaultControlPlane(
            changes,
            revisions,
            ChangeAuthority(engine.authorizer, revocations = RevocationReader.NONE)
        )
        // A service caller is stated now, by the system clock, as the gate that admits it reads it.
        RoleSync(ControlClient(LocalControlService(plane, IdentityGate())))
    }

    private fun head(key: SnapshotKey) = runBlocking { revisions.head(key) }!!.revision

    // A role acme does not hold yet: writing it grants anew, which the role class lifts.
    private val auditing = SourceRole("role-01HQ8B", "wms", "Auditing", listOf("warehouse.read"))

    @Test
    fun `a role written and a role deleted publish with no person`() = runBlocking<Unit> {
        val sync = sync()
        val acme = SnapshotKey.Tenant("acme")
        val before = head(acme)
        val changed = sync.roleChanged(
            "acme",
            SourceRole("role-01HQ8A", "wms", "Editing", listOf("warehouse.edit")),
            5530
        )
        changed.state.assert().isEqualTo(ChangeState.PUBLISHED)
        changed.escalations.map { it.reason }.assert().containsExactly(EscalationReason.ROLE_CLASS)
        changed.author.principal.assert().isEqualTo("sync-service")
        val deleted = sync.roleDeleted("acme", "role-01HQ8A", 5531)
        deleted.state.assert().isEqualTo(ChangeState.PUBLISHED)
        deleted.escalations.assert().isEmpty()
        head(acme).assert().isEqualTo(before + 2)
    }

    @Test
    fun `the catalog waits for a person, and a dry run stores nothing`() = runBlocking<Unit> {
        val sync = sync()
        val wms = SnapshotKey.App("wms")
        val catalog = Catalog(
            listOf(
                CatalogGroup(
                    "Warehouse",
                    listOf(
                        // What the catalog holds, the permissions acme's role lists included, and one more.
                        CatalogPermission("warehouse.read", "Read warehouses", listOf("wms:warehouse.read")),
                        CatalogPermission("warehouse.edit", "Edit warehouses", listOf("wms:warehouse.edit")),
                        CatalogPermission(
                            "inbound.ship",
                            "Ship inbound orders",
                            listOf("wms:inbound.ship"),
                            listOf(Resource("inbound/{id}")),
                        ),
                        CatalogPermission("warehouse.audit", "Audit warehouses", listOf("wms:warehouse.audit")),
                    ),
                ),
            ),
        )
        val before = head(wms)
        val waiting = sync.catalogChanged("wms", catalog, 5544)
        waiting.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        waiting.escalations.map { it.reason }.assert().contains(EscalationReason.NOT_ANALYZED)
        head(wms).assert().isEqualTo(before)
        val acme = SnapshotKey.Tenant("acme")
        val revision = head(acme)
        val dry = sync.wouldWrite("acme", auditing)
        dry.state.assert().isEqualTo(ChangeState.CHECKED)
        dry.escalations.map { it.reason }.assert().containsExactly(EscalationReason.ROLE_CLASS)
        head(acme).assert().isEqualTo(revision)
    }
}
