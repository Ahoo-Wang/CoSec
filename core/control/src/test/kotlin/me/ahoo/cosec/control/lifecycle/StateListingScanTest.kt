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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ChangeSamples
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ALICE_OP
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.store.ChangePage
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * R3-3: `GET /changes?state=` decides who may have the store scanned before anything is read, as `CTL-68` decides a
 * document listing. A tenant's principal scans its own tenant's document only; a platform principal scans the layers it
 * holds the layer read on; anyone else is refused and nothing is scanned. The layer read only admits the scan: each
 * change is still shown only if reading it by id would (`CTL-55`).
 */
class StateListingScanTest {
    private val memory = InMemoryControlStore()

    /** The store's scans: each call, and the documents its filter admitted to be read. */
    private val scans = AtomicInteger()
    private val admitted = CopyOnWriteArrayList<SnapshotKey>()
    private val store: ControlStore = object : ControlStore by memory {
        override suspend fun changes(
            state: ChangeState,
            after: String?,
            limit: Int,
            documents: (SnapshotKey) -> Boolean,
        ): ChangePage {
            scans.incrementAndGet()
            return memory.changes(state, after, limit) { key -> documents(key).also { if (it) admitted += key } }
        }
    }

    /** Every decision asked, as `<verb> <resource id>`. */
    private val decisions = CopyOnWriteArrayList<String>()

    private val someone = Actor(TenantId("acme"), PrincipalId("someone"))
    private val docs = listOf(
        SnapshotKey.Platform,
        SnapshotKey.App("order"),
        SnapshotKey.Tenant("acme"),
        SnapshotKey.Tenant("globex"),
    )

    init {
        runBlocking {
            memory.keepOpenChanges()
            docs.forEachIndexed { i, key ->
                val change = ChangeSamples.minimal.copy(
                    id = ChangeId("c$i"),
                    key = key,
                    author = someone,
                    state = ChangeState.CHECKED,
                )
                memory.putChange(null, change, null).assert().isTrue()
            }
        }
    }

    private fun listings(decide: (Action, Resource) -> ControlDecision): ChangeListings {
        val authorizer = ControlAuthorizer { _, action, resource ->
            decisions += "${action.verb} ${resource.id}"
            decide(action, resource)
        }
        return ChangeListings(
            store,
            ChangeAuthority(authorizer, revocations = RevocationReader.NONE),
            ControlDerived.workBudget(),
            ChangeRecordBudget.readWeight(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong()),
        )
    }

    private fun list(listings: ChangeListings, operator: Operator): List<String> = runBlocking {
        listings.list(operator, null, ChangeState.CHECKED, null, LIMIT) { true }.changes.map { it.id.value }
    }

    private fun refused(block: () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    @Test
    fun `a platform principal without any layer read is refused, and nothing is scanned - R3-3`() {
        val listings = listings { _, _ -> ControlDecision.DENY }
        refused { list(listings, PLATO) }.assert().isEqualTo(403)
        scans.get().assert().isEqualTo(0)
        // The three layer decisions, and none per document.
        decisions.assert().containsExactlyInAnyOrder("read tenant", "read app", "read platform")
    }

    @Test
    fun `a layer that cannot be decided is a 503, and nothing is scanned - R3-3`() {
        val listings = listings { _, resource ->
            if (resource.id == "app") ControlDecision.INDETERMINATE else ControlDecision.PERMIT
        }
        refused { list(listings, PLATO) }.assert().isEqualTo(503)
        scans.get().assert().isEqualTo(0)
    }

    @Test
    fun `the tenant layer read scans the tenant documents only, and shows what reading by id would - R3-3`() {
        // The tenant layer, and acme's own read; globex's is denied, and so is everything else.
        val listings = listings { action, resource ->
            when {
                action == ControlActions.READ && resource.id in setOf("tenant", "tenant/acme") -> ControlDecision.PERMIT
                else -> ControlDecision.DENY
            }
        }
        list(listings, PLATO).assert().containsExactly("c2")
        admitted.filterNot { it is SnapshotKey.Tenant }.assert().describedAs("never read").isEmpty()
        // Three layer decisions, then one per tenant document the scan read.
        decisions.assert().containsExactlyInAnyOrder(
            "read tenant",
            "read app",
            "read platform",
            "read tenant/acme",
            "read tenant/globex",
        )
    }

    @Test
    fun `the platform document's own read admits its changes to the scan - R3-3`() {
        val listings = listings { action, resource ->
            if (action == ControlActions.READ && resource.id == "platform") ControlDecision.PERMIT else ControlDecision.DENY
        }
        list(listings, PLATO).assert().containsExactly("c0")
        admitted.toSet().assert().containsExactly(SnapshotKey.Platform)
    }

    @Test
    fun `a tenant's principal scans its own tenant's document, with no layer decision - R3-3`() {
        val listings = listings { _, _ -> ControlDecision.PERMIT }
        list(listings, ALICE_OP).assert().containsExactly("c2")
        admitted.toSet().assert().containsExactly(SnapshotKey.Tenant("acme"))
        // The store skips, unread, what its tenant does not reach; one decision, its own document's.
        decisions.assert().containsExactly("read tenant/acme")
    }

    private companion object {
        const val LIMIT = 10
    }
}
