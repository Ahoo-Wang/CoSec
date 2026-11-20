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
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.policy
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A reclamation pass over many documents: one with nothing to reclaim costs one index and one head read, takes no
 * lease and writes nothing; one with something to reclaim is reclaimed, and its lease released when done (`CTL-26`,
 * storage.md: the holder deletes it); an index written as it is is not written at all.
 */
class ReclamationCostTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private fun count(call: String) = calls.getOrPut(call) { AtomicInteger() }.incrementAndGet()
    private fun calls(call: String) = calls[call]?.get() ?: 0

    private val store = object : ControlStore by memory {
        override suspend fun head(key: SnapshotKey) = memory.head(key).also { count("head") }
        override suspend fun index(key: SnapshotKey) = memory.index(key).also { count("index") }
        override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex) =
            memory.putIndex(key, expected, index).also { count("putIndex") }
        override suspend fun acquireLease(name: String, holder: String, duration: Duration) =
            memory.acquireLease(name, holder, duration).also { count("acquireLease") }
        override suspend fun releaseLease(name: String, holder: String) =
            memory.releaseLease(name, holder).also { count("releaseLease") }
    }
    private val revisions = Revisions(store, clock, RetentionPolicy(maxCount = 2, minAge = Duration.ofDays(1)))

    private suspend fun publish(times: Int) = repeat(times) {
        revisions.publish(ACME, revisions.head(ACME), candidate(ACME, listOf(policy("p$it"))), publication())
        clock.now = clock.now.plus(Duration.ofDays(2))
    }

    @Test
    fun `a document with nothing to reclaim costs one index and one head read, and no lease or write`() = runBlocking<Unit> {
        publish(2)
        calls.clear()

        revisions.reclaim(ACME).any.assert().isFalse()

        calls("index").assert().isEqualTo(1)
        calls("head").assert().isEqualTo(1)
        calls("acquireLease").assert().isEqualTo(0)
        calls("putIndex").assert().isEqualTo(0)
    }

    @Test
    fun `a document with something to reclaim is reclaimed, reads its head twice at most, and releases its lease`() =
        runBlocking<Unit> {
            publish(4)
            calls.clear()

            revisions.reclaim(ACME).records.size.assert().isEqualTo(2)

            calls("acquireLease").assert().isEqualTo(1)
            calls("releaseLease").assert().isEqualTo(1)
            calls("head").assert().isLessThanOrEqualTo(1)
            // Released: another instance takes it at once, not only once it expires.
            memory.acquireLease("reclaim:$ACME", "another", Duration.ofMinutes(5)).assert().isTrue()
        }

    @Test
    fun `an index reclamation leaves as it is is not written`() = runBlocking<Unit> {
        // More revisions than max-count, all younger than min-age: nothing is removed.
        repeat(
            4
        ) { revisions.publish(ACME, revisions.head(ACME), candidate(ACME, listOf(policy("q$it"))), publication()) }
        calls.clear()

        revisions.reclaim(ACME).any.assert().isFalse()

        calls("acquireLease").assert().isEqualTo(1)
        calls("putIndex").assert().isEqualTo(0)
    }
}
