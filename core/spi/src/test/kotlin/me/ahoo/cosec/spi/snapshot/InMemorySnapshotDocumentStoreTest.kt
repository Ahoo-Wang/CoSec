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

package me.ahoo.cosec.spi.snapshot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A subscriber that throws does not report a write that happened as failed, nor keep the others from being told, and
 * a later write notifies them all again (#1400).
 */
class InMemorySnapshotDocumentStoreTest {
    private val store = InMemorySnapshotDocumentStore()
    private val key = SnapshotKey.Platform
    private val document = SnapshotDocument(2, 1, Instant.parse("2026-11-01T00:00:00Z"), "{}".toByteArray())

    private class Recording : SnapshotChangeListener {
        val changes = CopyOnWriteArrayList<SnapshotKey>()
        var resets = 0

        override fun onChanged(keys: Collection<SnapshotKey>) {
            changes += keys
        }

        override fun onReset() {
            resets++
        }
    }

    private class Failing : SnapshotChangeListener {
        override fun onChanged(keys: Collection<SnapshotKey>) = throw IllegalStateException("subscriber failed")

        override fun onReset() = throw IllegalStateException("subscriber failed")
    }

    /** The failure goes to the writer thread's handler, which a test replaces to see it. */
    private fun <T> handled(block: () -> T): Pair<T, List<Throwable>> {
        val thread = Thread.currentThread()
        val before = thread.uncaughtExceptionHandler
        val seen = ArrayList<Throwable>()
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> seen += error }
        try {
            return block() to seen
        } finally {
            thread.uncaughtExceptionHandler = before
        }
    }

    @Test
    fun `a put returns, with the document stored, and the healthy subscriber is told`() {
        val healthy = Recording()
        store.subscribe(Failing())
        store.subscribe(healthy)

        val (digest, failures) = handled { store.put(key, document) }

        digest.assert().isEqualTo(InMemorySnapshotDocumentStore.digestOf(document))
        store[key].assert().isEqualTo(document)
        healthy.changes.assert().containsExactly(key)
        failures.map { it.message }.assert().containsExactly("subscriber failed")
    }

    @Test
    fun `a remove returns, with the document removed, and the healthy subscriber is told`() {
        store.put(key, document)
        val healthy = Recording()
        store.subscribe(Failing())
        store.subscribe(healthy)

        val (_, failures) = handled { store.remove(key) }

        store[key].assert().isNull()
        healthy.changes.assert().containsExactly(key)
        failures.assert().hasSize(1)
    }

    @Test
    fun `a reset tells every subscriber, whichever fails`() {
        val before = Recording()
        val after = Recording()
        store.subscribe(before)
        store.subscribe(Failing())
        store.subscribe(after)

        val (_, failures) = handled { store.reset() }

        before.resets.assert().isEqualTo(1)
        after.resets.assert().isEqualTo(1)
        failures.assert().hasSize(1)
    }

    @Test
    fun `a failing subscriber is told again by the next write, and one that unsubscribed is not`() {
        val healthy = Recording()
        val gone = Recording()
        store.subscribe(Failing())
        store.subscribe(healthy)
        store.subscribe(gone).close()

        val (_, failures) = handled {
            store.put(key, document)
            store.put(key, document)
        }

        healthy.changes.assert().hasSize(2)
        failures.assert().hasSize(2)
        gone.changes.assert().isEmpty()
    }

    /** A VM error is not a subscriber's mistake: it goes through, as it does from the Redis store. */
    @Test
    fun `a virtual machine error is not swallowed`() {
        store.subscribe(object : SnapshotChangeListener {
            override fun onChanged(keys: Collection<SnapshotKey>) = throw OutOfMemoryError("test")

            override fun onReset() = Unit
        })

        runCatching { store.put(key, document) }.exceptionOrNull().assert().isInstanceOf(OutOfMemoryError::class.java)
    }
}
