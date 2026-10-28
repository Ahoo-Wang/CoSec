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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import me.ahoo.cosec.spi.snapshot.SnapshotVersion

import me.ahoo.cosec.runtime.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** RFC 0003 §4.4 F1–F6, and the rule that a document once seen never reads as absent. */
class SnapshotSourceFailureTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1, "a1"))
    }

    private fun DocumentState<*>?.failure(): Throwable = (this as DocumentState.Failed).error

    @Test
    fun f1ColdReadFailureFailsTheRequestAndIsNotCached() {
        val metrics = RecordingMetrics()
        testSource(store, metrics = metrics).use { source ->
            store.failure = IllegalStateException("redis down")

            val snapshot = source.load("app-a").join()

            snapshot.policies.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
            snapshot.policies.failure().cause.assert().isInstanceOf(IllegalStateException::class.java)
            snapshot.app.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
            source.peek().assert().isNull()
            source.entries().assert().isEmpty()
            metrics.loads.assert().containsExactly(
                SnapshotKind.PLATFORM to LoadOutcome.FAILED,
                SnapshotKind.APP to LoadOutcome.FAILED
            )

            store.failure = null
            source.load("app-a").join().appCompiled.entries.assert().containsExactly("a1")
            store.reads.get().assert().isEqualTo(4)
        }
    }

    @Test
    fun f1StoreThatThrowsSynchronouslyStillFailsClosed() {
        val throwing = object : SnapshotDocumentStore by store {
            override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> = throw IllegalStateException(
                "broken store"
            )
        }
        testSource(throwing).use { source ->
            source.load().join().policies.failure().cause.assert().isInstanceOf(IllegalStateException::class.java)
            source.peek().assert().isNull()
        }
    }

    @Test
    fun f2AbsentDocumentIsAbsent() {
        val empty = ControllableStore()
        testSource(empty).use { source ->
            val snapshot = source.load("app-a").join()
            snapshot.policies.assert().isEqualTo(DocumentState.Absent)
            snapshot.app.assert().isEqualTo(DocumentState.Absent)
            snapshot.version.assert().isEqualTo(SnapshotVersion(0, 0))
            source.revalidate().join()
            source.peek("app-a")!!.policies.assert().isEqualTo(DocumentState.Absent)
        }
    }

    @Test
    fun f3UndecodableDocumentIsInstalledAsFailed() {
        store.undecodable = true
        testSource(store).use { source ->
            source.load().join().policies.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
            val installed = source.peek()!!
            installed.policies.failure().cause!!.cause.assert().isInstanceOf(SnapshotFormatException::class.java)

            store.undecodable = false
            store.put(SnapshotKey.Platform, document(2))
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun f3DocumentThatDoesNotCompileIsInstalledAsFailed() {
        val metrics = RecordingMetrics()
        store.put(SnapshotKey.Platform, document(2, "THROW"))
        testSource(store, metrics = metrics).use { source ->
            val failure = source.load().join().policies.failure()
            failure.assert().isInstanceOf(SnapshotUnavailableException::class.java)
            failure.cause.assert().isInstanceOf(IllegalArgumentException::class.java)
            source.peek()!!.version.assert().isEqualTo(SnapshotVersion(2, 0))
            // The cold read may come from a local copy, so it is confirmed at once, and a document that failed to
            // compile is always read in full and compiled again.
            metrics.compiles.assert().containsOnly(SnapshotKind.PLATFORM to false).hasSize(2)
        }
    }

    @Test
    fun f4BrokenEntryFailsOnlyThatEntry() {
        store.put(SnapshotKey.Platform, document(2, "a,!b,c"))
        testSource(store).use { source ->
            val compiled = source.load().join().policiesCompiled
            compiled.entries.assert().containsExactly("a", "b", "c")
            compiled.broken.assert().containsExactly("b")
        }
    }

    @Test
    fun f5UndecodableNewRevisionReplacesTheInstalledOne() {
        testSource(store).use { source ->
            source.load().join()
            store.undecodable = true
            store.put(SnapshotKey.Platform, document(2))

            source.peek()!!.policies.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
        }
    }

    @Test
    fun f5NewRevisionThatDoesNotCompileReplacesTheInstalledOne() {
        testSource(store).use { source ->
            source.load().join()
            store.put(SnapshotKey.Platform, document(2, "THROW"))

            val snapshot = source.peek()!!
            snapshot.policies.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
            snapshot.version.shared.assert().isEqualTo(2)

            source.revalidate().join()
            source.peek()!!.policies.assert().isInstanceOf(DocumentState.Failed::class.java)

            store.put(SnapshotKey.Platform, document(3))
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(3)
        }
    }

    @Test
    fun f6ReloadReadFailureKeepsTheInstalledDocumentAndLagGrows() {
        val clock = ManualClock()
        testSource(store, clock = clock).use { source ->
            val installed = source.load("app-a").join()
            store.failure = IllegalStateException("redis down")
            clock.advance(Duration.ofHours(1))

            store.notifyChanged(SnapshotKey.Platform)
            source.revalidate().join()

            val snapshot = source.peek("app-a")!!
            snapshot.policiesEntry.assert().isSameAs(installed.policiesEntry)
            snapshot.appEntry.assert().isSameAs(installed.appEntry)
            snapshot.lagAt(clock.now).assert().isEqualTo(Duration.ofHours(1))
        }
    }

    @Test
    fun installedDocumentNeverExpires() {
        val clock = ManualClock()
        testSource(store, clock = clock).use { source ->
            source.load().join()
            store.failure = IllegalStateException("redis down")
            repeat(10) {
                clock.advance(Duration.ofDays(1))
                source.tick().join()
            }
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)
            source.peek()!!.lagAt(clock.now).assert().isEqualTo(Duration.ofDays(10))
        }
    }

    @Test
    fun seenDocumentThatDisappearsFailsClosed() {
        val metrics = RecordingMetrics()
        testSource(store, metrics = metrics).use { source ->
            source.load("app-a").join()
            store.remove(APP_A)
            store.remove(SnapshotKey.Platform)

            val snapshot = source.peek("app-a")!!
            snapshot.policies.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
            snapshot.app.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
            metrics.lost.assert().containsExactlyInAnyOrder(SnapshotKey.Platform to 1L, APP_A to 1L)

            val lostEntry = source.peek()!!.policiesEntry
            source.revalidate().join()
            source.peek()!!.policiesEntry.assert().isSameAs(lostEntry)
            metrics.lost.assert().hasSize(2)

            store.put(SnapshotKey.Platform, document(2))
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(2)
        }
    }

    @Test
    fun seenAppDocumentStillFailsClosedAfterEviction() {
        store.put(APP_B, document(1, "b1"))
        testSource(store, options = SnapshotOptions(revalidateInterval = Duration.ZERO, maxApps = 1)).use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            await { !source.holds(APP_A) }

            store.remove(APP_A)

            source.load("app-a").join().app.failure().assert().isInstanceOf(SnapshotUnavailableException::class.java)
        }
    }

    @Test
    fun undecodableDocumentThatDisappearsFailsClosed() {
        store.undecodable = true
        testSource(store).use { source ->
            source.load().join()
            store.remove(SnapshotKey.Platform)
            store.undecodable = false
            source.revalidate().join()
            source.peek()!!.policies.assert().isInstanceOf(DocumentState.Failed::class.java)
        }
    }

    @Test
    fun storeAnsweringUnchangedForAnUnconditionalReadFailsClosed() {
        val confused = object : SnapshotDocumentStore by store {
            override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> = CompletableFuture.completedFuture(
                SnapshotRead.Unchanged
            )
        }
        testSource(confused).use { source ->
            source.load().join().policies.assert().isInstanceOf(DocumentState.Failed::class.java)
            source.peek().assert().isNull()
        }
    }

    @Test
    fun storeAnsweringUnchangedForAnAbsentDocumentConfirmsIt() {
        val clock = ManualClock()
        val empty = ControllableStore()
        val unchangedWhenAbsent = object : SnapshotDocumentStore by empty {
            override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> =
                empty.readIfChanged(
                    key,
                    known
                ).thenApply { if (it == SnapshotRead.Absent) SnapshotRead.Unchanged else it }
        }
        testSource(unchangedWhenAbsent, clock = clock).use { source ->
            source.load().join()
            clock.advance(Duration.ofMinutes(1))
            source.revalidate().join()
            source.peek()!!.policies.assert().isEqualTo(DocumentState.Absent)
            source.peek()!!.lagAt(clock.now).assert().isEqualTo(Duration.ZERO)
        }
    }
}
