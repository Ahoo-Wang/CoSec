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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.binding
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * #1207: the references a revision drops are removed after its head write, so a later revision of the same document
 * may already have indexed them again. Its removal must not take that back: the index stays a superset of what every
 * head refers to (RFC 0007 §5.8, `CTL-5`), or a policy a head still binds could be removed. The interleaving is set by
 * latches on the store, not by timing.
 */
class ReferrerCleanupRaceTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val shared = PolicyId("shared")

    /** Holds the first write of [shared]'s referrers once [armed], until [resume]. */
    private val store = object : ControlStore by memory {
        var armed = false
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override suspend fun putReferrers(
            target: SnapshotKey,
            policy: PolicyId,
            expected: SnapshotDigest?,
            referrers: Map<SnapshotKey, Long>,
        ): Boolean {
            if (armed && target == SnapshotKey.Platform && policy == shared) {
                armed = false
                paused.complete(Unit)
                resume.await()
            }
            return memory.putReferrers(target, policy, expected, referrers)
        }
    }
    private val revisions = Revisions(store, clock)

    private suspend fun publish(key: SnapshotKey, document: PolicyDocument, change: String): PublishOutcome =
        revisions.publish(key, revisions.head(key), document, publication(change))

    @Test
    fun `a revision's late removal never drops a reference a later revision indexed again - #1207`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("shared"), policy("other"))), "p1")
        publish(ACME, candidate(ACME, bindings = listOf(binding("b", "shared"))), "t1")
        revisions.referrers(SnapshotKey.Platform, shared).assert().containsExactly(ACME)

        // Revision 2 drops the binding: its head is written, and its removal of the reference waits.
        store.armed = true
        val dropping = async { publish(ACME, candidate(ACME), "t2") }
        store.paused.await()
        revisions.head(ACME)!!.revision.assert().isEqualTo(2)

        // Revision 3, over revision 2, binds it again: it indexes the reference before its head write.
        publish(ACME, candidate(ACME, bindings = listOf(binding("b", "shared"))), "t3")
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
        store.resume.complete(Unit)
        dropping.await().assert().isInstanceOf(PublishOutcome.Published::class.java)

        revisions.head(ACME)!!.document.bindings.map { it.policy }.assert().containsExactly(shared)
        revisions.referrers(SnapshotKey.Platform, shared).assert().containsExactly(ACME)
        val removal = publish(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("other"))), "p2")
        (removal as PublishOutcome.Referenced).referrers.assert().isEqualTo(mapOf(shared to setOf(ACME)))
    }

    @Test
    fun `a revision's removal that waits still removes the reference when nothing indexed it again`() =
        runBlocking<Unit> {
            publish(
                SnapshotKey.Platform,
                candidate(SnapshotKey.Platform, listOf(policy("shared"), policy("other"))),
                "p1"
            )
            publish(ACME, candidate(ACME, bindings = listOf(binding("b", "shared"))), "t1")

            store.armed = true
            val dropping = async { publish(ACME, candidate(ACME), "t2") }
            store.paused.await()
            // Revision 3 refers to something else: the reference revision 2 dropped stays dropped.
            publish(ACME, candidate(ACME, bindings = listOf(binding("o", "other"))), "t3")
            store.resume.complete(Unit)
            dropping.await().assert().isInstanceOf(PublishOutcome.Published::class.java)

            revisions.referrers(SnapshotKey.Platform, shared).assert().isEmpty()
            publish(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("other"))), "p2")
                .assert().isInstanceOf(PublishOutcome.Published::class.java)
        }

    @Test
    fun `a reference indexed without a revision is removed by a later drop and raised by a later reference`() =
        runBlocking<Unit> {
            publish(SnapshotKey.Platform, candidate(SnapshotKey.Platform, listOf(policy("shared"), policy("other"))), "p1")
            publish(ACME, candidate(ACME, bindings = listOf(binding("b", "shared"))), "t1")
            // As 8.0.0-alpha.3 indexed it: a set of keys, read at revision 0.
            val indexed = memory.referrers(SnapshotKey.Platform, shared)!!
            memory.putReferrers(SnapshotKey.Platform, shared, indexed.digest, mapOf(ACME to 0L)).assert().isTrue()
            publish(ACME, candidate(ACME, bindings = listOf(binding("b", "shared"), binding("c", "other"))), "t2")
            memory.referrers(SnapshotKey.Platform, shared)!!.value.assert().isEqualTo(mapOf(ACME to 0L))
            publish(ACME, candidate(ACME, bindings = listOf(binding("c", "other"))), "t3")
            revisions.referrers(SnapshotKey.Platform, shared).assert().isEmpty()
            publish(ACME, candidate(ACME, bindings = listOf(binding("b", "shared"))), "t4")
            memory.referrers(SnapshotKey.Platform, shared)!!.value.assert().isEqualTo(mapOf(ACME to 4L))
        }
}
