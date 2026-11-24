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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.store.ChangePage
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * R3-3: a listing by state reads at most four pages of changes per request, whatever the caller may see. A scan the
 * bound stops answers what it found, short or empty, with the position where it stopped, and the next request, on any
 * replica, continues from there to the later changes the caller sees.
 */
class StateListingBoundTest {
    private val memory = InMemoryControlStore()

    /** The changes the store read for the current request. */
    private val read = AtomicInteger()
    private val store: ControlStore = object : ControlStore by memory {
        override suspend fun changes(
            state: ChangeState,
            after: String?,
            limit: Int,
            documents: (SnapshotKey) -> Boolean,
        ): ChangePage = memory.changes(state, after, limit, documents).also { read.addAndGet(it.changes.size) }
    }

    /** Six tenants whose documents the caller may not read, then `zeta`'s, which it may. */
    private val unseen = (1..6).map { SnapshotKey.Tenant("t$it") }
    private val seen = SnapshotKey.Tenant("zeta")

    init {
        runBlocking {
            memory.keepOpenChanges()
            (unseen + seen).forEach { key ->
                val change = ChangeSamples.minimal.copy(
                    id = ChangeId("c-${key.tenantId}"),
                    key = key,
                    author = Actor(TenantId(key.tenantId), PrincipalId("someone")),
                    state = ChangeState.CHECKED,
                )
                memory.putChange(null, change, null).assert().isTrue()
            }
        }
    }

    /** A replica of the control plane: its own listings over the shared store, and the same policies. */
    private fun replica() = ChangeListings(
        store,
        ChangeAuthority(
            ControlAuthorizer { _, action, resource ->
                val readable = resource.id == "tenant" || resource.id == "tenant/zeta"
                if (action == ControlActions.READ && readable) ControlDecision.PERMIT else ControlDecision.DENY
            },
            revocations = RevocationReader.NONE,
        ),
        ControlDerived.workBudget(),
        ChangeRecordBudget.readWeight(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong()),
    )

    private fun page(listings: ChangeListings, after: String?): ChangePage = runBlocking {
        read.set(0)
        listings.list(PLATO, null, ChangeState.CHECKED, after, LIMIT) { true }
    }

    @Test
    fun `a scan stopped by its bound answers where it stopped, and the next request goes on from there - R3-3`() {
        val first = page(replica(), null)
        // Four changes read, none the caller sees: an empty page, and the position the scan reached.
        read.get().assert().isEqualTo(SCAN_PAGES * LIMIT)
        first.changes.assert().isEmpty()
        val next = first.next.assert().isNotNull().actual()
        next.assert().isEqualTo("tenant:t4#c-t4")
        // Another replica continues from it, reads what is left, and reaches the change the caller sees.
        val second = page(replica(), next)
        read.get().assert().isLessThanOrEqualTo(SCAN_PAGES * LIMIT)
        second.changes.map { it.id.value }.assert().containsExactly("c-zeta")
        second.next.assert().isNull()
    }

    @Test
    fun `no request reads more than its bound, whatever it may see - R3-3`() {
        val listings = replica()
        var after: String? = null
        val shown = ArrayList<String>()
        var requests = 0
        do {
            val page = page(listings, after)
            read.get().assert().isLessThanOrEqualTo(SCAN_PAGES * LIMIT)
            page.changes.mapTo(shown) { it.id.value }
            after = page.next
            requests++
        } while (after != null && requests < unseen.size + 2)
        shown.assert().containsExactly("c-zeta")
        after.assert().describedAs("the listing ends").isNull()
    }

    private companion object {
        const val LIMIT = 1

        /** ChangeListings' bound: four pages of the limit. */
        const val SCAN_PAGES = 4
    }
}
