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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.binding
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `CTL-5`, RFC 0007 §5.8: a tenant's reference to an app's permission policy (`perm:<app>/<id>`) is indexed under the
 * app only, since only the app's document can define that prefix (`TNT-2`). The app can still not remove it, and an
 * app's removal check reads no tenant's head, since none can define the id itself.
 */
class AppPermissionReferencesTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val tenantHeads = AtomicInteger()
    private val store = object : ControlStore by memory {
        override suspend fun head(key: SnapshotKey): StoredDocument? =
            memory.head(key).also { if (key is SnapshotKey.Tenant) tenantHeads.incrementAndGet() }
    }
    private val revisions = Revisions(store, clock)
    private val order = SnapshotKey.App("order")
    private val read = PolicyId("perm:order/read")
    private val index = ReferrerIndex(memory, RevisionWrites(memory, 1)) { null }

    private val tenant = candidate(
        ACME,
        policies = listOf(policy("own")),
        bindings = listOf(binding("r", read.value, app = "order"), binding("s", "shared", app = "order")),
    )

    private suspend fun published(key: SnapshotKey, document: me.ahoo.cosec.document.PolicyDocument): PublishOutcome =
        revisions.publish(key, revisions.head(key), document, publication())

    @Test
    fun `a tenant's reference to an app permission is indexed under the app only, every other one as before`() {
        index.references(tenant).assert().containsExactlyInAnyOrder(
            ReferrerIndex.Reference(SnapshotKey.Platform, PolicyId("own"), definesItself = true),
            ReferrerIndex.Reference(order, read),
            ReferrerIndex.Reference(SnapshotKey.Platform, PolicyId("shared")),
            ReferrerIndex.Reference(order, PolicyId("shared")),
        )
    }

    @Test
    fun `the app cannot remove a permission a tenant refers to, and the platform holds no member for it`() =
        runBlocking<Unit> {
            published(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("shared"))))
            published(order, candidate(order, listOf(policy(read.value))))
            published(ACME, tenant).assert().isInstanceOf(PublishOutcome.Published::class.java)

            memory.referrers(SnapshotKey.Platform, read).assert().isEmpty()
            memory.referrers(order, read).assert().containsExactly(ReferrerEntry(ACME, 1))
            val removed = published(order, candidate(order, emptyList()))
            removed.assert().isInstanceOf(PublishOutcome.Referenced::class.java)
            (removed as PublishOutcome.Referenced).referrers.getValue(read).assert().containsExactly(ACME)
        }

    @Test
    fun `an app's removal check reads no tenant's head`() = runBlocking<Unit> {
        published(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("shared"))))
        published(order, candidate(order, listOf(policy(read.value))))
        published(ACME, tenant)
        tenantHeads.set(0)

        published(order, candidate(order, emptyList())).assert().isInstanceOf(PublishOutcome.Referenced::class.java)

        tenantHeads.get().assert().isEqualTo(0)
    }

    /** What `cosec-migrate repair-references` converts: the sets a head touches, and whether a referrer defines an id. */
    @Test
    fun `the sets a head touches, and whether a referrer defines an id itself`() = runBlocking<Unit> {
        published(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("shared"))))
        published(order, candidate(order, listOf(policy(read.value))))
        published(ACME, tenant)
        revisions.referenceKeys(
            SnapshotKey.Platform
        ).assert().containsExactly(SnapshotKey.Platform to PolicyId("shared"))
        revisions.referenceKeys(order).assert().contains(order to read)
        revisions.referenceKeys(ACME).assert()
            .contains(
                SnapshotKey.Platform to PolicyId("own"),
                order to read,
                SnapshotKey.Platform to PolicyId("shared")
            )
        revisions.referenceKeys(SnapshotKey.Tenant("absent")).assert().isEmpty()
        revisions.definesItself(ACME, PolicyId("own")).assert().isTrue()
        revisions.definesItself(ACME, PolicyId("shared")).assert().isFalse()
        revisions.definesItself(order, read).assert().isFalse()
    }

    /** `CTL-44`: a member whose removal fails stays recorded, and the next publish that drops it removes it. */
    @Test
    fun `a drop whose removal fails keeps its record, and a later drop removes the member`() = runBlocking<Unit> {
        published(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("shared"))))
        val binds = candidate(ACME, bindings = listOf(binding("s", "shared")))
        published(ACME, binds)
        var failing = true
        val flaky = object : ControlStore by memory {
            override suspend fun removeReferrers(target: SnapshotKey, policy: PolicyId, entries: Set<ReferrerEntry>) {
                check(!failing) { "Redis is down" }
                memory.removeReferrers(target, policy, entries)
            }
        }
        val flakyRevisions = Revisions(flaky, clock)
        flakyRevisions.publish(ACME, flakyRevisions.head(ACME), candidate(ACME), publication())
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
        memory.referrers(SnapshotKey.Platform, PolicyId("shared")).assert().containsExactly(ReferrerEntry(ACME, 1))
        memory.index(ACME)!!.value.indexed.map { it.policy }.assert().containsExactly(PolicyId("shared"))

        failing = false
        flakyRevisions.publish(ACME, flakyRevisions.head(ACME), candidate(ACME), publication())
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
        memory.referrers(SnapshotKey.Platform, PolicyId("shared")).assert().isEmpty()
        memory.index(ACME)!!.value.indexed.assert().isEmpty()
    }
}
