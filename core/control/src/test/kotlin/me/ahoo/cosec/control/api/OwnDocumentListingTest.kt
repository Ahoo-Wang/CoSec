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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.change.ALICE_OP
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * R3-4, `CTL-68`: a tenant's principal shown only its own tenant's document is answered with one decision and one read
 * of whether the store indexes that document, never the whole document list.
 */
class OwnDocumentListingTest {
    private val memory = InMemoryControlStore()
    private val listReads = AtomicInteger()
    private val indexReads = CopyOnWriteArrayList<SnapshotKey>()
    private val store: ControlStore = object : ControlStore by memory {
        override suspend fun documents(): Set<SnapshotKey> {
            listReads.incrementAndGet()
            return memory.documents()
        }

        override suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>? {
            indexReads += key
            return memory.index(key)
        }
    }
    private val revisions = Revisions(store)
    private val clock = MutableClock()
    private val decisions = AtomicInteger()

    /** No layer read; acme's own document may be read. */
    private val listing = DocumentListing(
        revisions,
        ChangeAuthority(
            ControlAuthorizer { _, _, resource ->
                decisions.incrementAndGet()
                if (resource.id == "tenant/acme") ControlDecision.PERMIT else ControlDecision.DENY
            },
            revocations = RevocationReader.NONE,
        ),
        clock,
    )

    private fun publish(key: SnapshotKey) = runBlocking {
        revisions.publish(key, revisions.head(key), StoreFixtures.candidate(key), StoreFixtures.publication())
    }

    private fun reset() {
        listReads.set(0)
        indexReads.clear()
        decisions.set(0)
    }

    @Test
    fun `its own document is one decision and one index read, never the document list - R3-4`() = runBlocking<Unit> {
        (1..50).forEach { publish(SnapshotKey.Tenant("t$it")) }
        publish(SnapshotKey.Tenant("acme"))
        reset()
        val page = listing.list(ALICE_OP, DocumentKind.TENANT, null, 20)
        page.documents.map { it.toString() }.assert().containsExactly("tenant:acme")
        page.next.assert().isNull()
        page.asOf.assert().isEqualTo(clock.now)
        listReads.get().assert().describedAs("the document list is never read").isEqualTo(0)
        indexReads.assert().containsExactly(SnapshotKey.Tenant("acme"))
        // The layer's decision, then its own document's.
        decisions.get().assert().isEqualTo(2)
    }

    @Test
    fun `its own document not indexed, or a cursor past it, is an empty page - R3-4`() = runBlocking<Unit> {
        listing.list(ALICE_OP, DocumentKind.TENANT, null, 20).documents.assert().isEmpty()
        publish(SnapshotKey.Tenant("acme"))
        listing.list(ALICE_OP, DocumentKind.TENANT, "tenant:acme", 20).documents.assert().isEmpty()
        listing.list(ALICE_OP, DocumentKind.TENANT, "tenant:a", 20).documents.map { it.toString() }
            .assert().containsExactly("tenant:acme")
        listReads.get().assert().isEqualTo(0)
    }
}
