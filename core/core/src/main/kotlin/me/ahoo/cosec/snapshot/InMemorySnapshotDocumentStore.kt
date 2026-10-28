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

package me.ahoo.cosec.snapshot

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [SnapshotDocumentStore] in memory: for tests, local policy files and the policy test kit. Reads complete on the
 * caller's thread. Writes notify subscribers on the writer's thread after the new value is readable.
 */
class InMemorySnapshotDocumentStore : SnapshotDocumentStore {
    private class Stored(val document: SnapshotDocument, val digest: SnapshotDigest)

    private val documents = ConcurrentHashMap<SnapshotKey, Stored>()
    private val listeners = CopyOnWriteArrayList<SnapshotChangeListener>()

    /** Stores [document] under [key] and notifies subscribers. Returns the digest of the stored value. */
    fun put(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest {
        val stored = Stored(document, digestOf(document))
        documents[key] = stored
        notifyChanged(key)
        return stored.digest
    }

    /** Removes [key] and notifies subscribers. */
    fun remove(key: SnapshotKey) {
        documents.remove(key)
        notifyChanged(key)
    }

    /** The stored document, if any. */
    operator fun get(key: SnapshotKey): SnapshotDocument? = documents[key]?.document

    /** Tells subscribers that notifications may have been lost. */
    fun reset() {
        listeners.forEach { it.onReset() }
    }

    override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> =
        CompletableFuture.completedFuture(readNow(key))

    override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> {
        val read = readNow(key)
        if (read is SnapshotRead.Present && read.digest == known) {
            return CompletableFuture.completedFuture(SnapshotRead.Unchanged)
        }
        return CompletableFuture.completedFuture(read)
    }

    override fun subscribe(listener: SnapshotChangeListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun readNow(key: SnapshotKey): SnapshotRead {
        val stored = documents[key] ?: return SnapshotRead.Absent
        return SnapshotRead.Present(stored.document, stored.digest)
    }

    private fun notifyChanged(key: SnapshotKey) {
        val keys = listOf(key)
        listeners.forEach { it.onChanged(keys) }
    }

    companion object {
        /** SHA-256 over every field, so documents that differ in any field have different digests. */
        fun digestOf(document: SnapshotDocument): SnapshotDigest {
            val header = ByteBuffer.allocate(Int.SIZE_BYTES + Long.SIZE_BYTES * 2 + Int.SIZE_BYTES)
                .putInt(document.schema)
                .putLong(document.revision)
                .putLong(document.publishedAt.epochSecond)
                .putInt(document.publishedAt.nano)
                .array()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(header)
            digest.update(document.bodyBytes())
            return SnapshotDigest(digest.digest())
        }
    }
}
