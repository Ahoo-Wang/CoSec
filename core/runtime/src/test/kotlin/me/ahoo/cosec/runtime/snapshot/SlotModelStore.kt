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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import java.util.concurrent.CompletableFuture

/**
 * The store of the slot model: every write to a key is a new version of it, in order, and every read the source starts
 * is recorded with the version it answered. A held read answers when the script releases it, with the version current
 * then or the one current when it started (either is a linearization a real store may give); a hung read is never
 * released until the run settles.
 */
internal class SlotModelStore(private val events: () -> Long, private val now: () -> Long) : SnapshotDocumentStore {
    /** What a key holds at one version. */
    sealed interface Content {
        data class Document(val document: SnapshotDocument, val digest: SnapshotDigest) : Content

        data object Absent : Content

        data object Undecodable : Content
    }

    /** One read the source started: when, of what, and what it answered. */
    inner class Read(
        val key: SnapshotKey,
        val known: SnapshotDigest?,
        val startedAt: Long,
        val startedNanos: Long,
        private val startVersion: Int
    ) {
        val future = CompletableFuture<SnapshotRead>()

        /** The version the answer came from; -1 until answered, and for a failed read. */
        var answeredVersion = -1
            private set
        var answered = false
            private set
        var failed = false
            private set

        /** Given up by the source before its answer came: the answer must change nothing. */
        var abandoned = false
        var hung = false

        fun release(atStart: Boolean) {
            pending.remove(this)
            answered = true
            val failure = failure
            if (failure != null) {
                failed = true
                future.completeExceptionally(failure)
                return
            }
            val version = if (atStart) startVersion else versionOf(key)
            answeredVersion = version
            when (val content = contentAt(key, version)) {
                is Content.Document ->
                    future.complete(
                        if (content.digest == known) {
                            SnapshotRead.Unchanged
                        } else {
                            SnapshotRead.Present(
                                content.document,
                                content.digest
                            )
                        }
                    )
                Content.Absent -> future.complete(SnapshotRead.Absent)
                Content.Undecodable -> {
                    future.completeExceptionally(
                        IllegalStateException("decode", SnapshotFormatException("unknown schema"))
                    )
                }
            }
        }

        override fun toString(): String = "read of $key from event $startedAt"
    }

    private val versions = HashMap<SnapshotKey, MutableList<Content>>()
    private val revisions = HashMap<SnapshotKey, Long>()
    private val listeners = mutableListOf<SnapshotChangeListener>()

    val reads = mutableListOf<Read>()
    val pending = mutableListOf<Read>()

    /** Reads answer at once while false. */
    var hold = true

    /** When set, every read answers with it. */
    var failure: Throwable? = null

    /** Writes are not notified while true. */
    var dropNotifications = false

    /** What the source's listener is told while it subscribes, before [subscribe] returns. */
    var duringSubscribe: (SnapshotChangeListener) -> Unit = {}

    fun versionOf(key: SnapshotKey): Int = versions[key]?.lastIndex ?: -1

    fun contentAt(key: SnapshotKey, version: Int): Content = if (version < 0) {
        Content.Absent
    } else {
        versions.getValue(
            key
        )[version]
    }

    fun current(key: SnapshotKey): Content = contentAt(key, versionOf(key))

    /** Every version of [key], oldest first. */
    fun versionsOf(key: SnapshotKey): List<Content> = versions[key].orEmpty()

    fun put(key: SnapshotKey) {
        val revision = (revisions[key] ?: 0) + 1
        revisions[key] = revision
        val document = document(revision, "$key-$revision")
        write(key, Content.Document(document, InMemorySnapshotDocumentStore.digestOf(document)))
    }

    fun remove(key: SnapshotKey) = write(key, Content.Absent)

    fun corrupt(key: SnapshotKey) = write(key, Content.Undecodable)

    /** A failover: the key holds again the document it held before its latest one, a new version with old content. */
    fun regress(key: SnapshotKey): Boolean {
        val documents = versionsOf(key).filterIsInstance<Content.Document>().distinct()
        if (documents.size < 2) return false
        write(key, documents[documents.size - 2])
        return true
    }

    private fun write(key: SnapshotKey, content: Content) {
        versions.getOrPut(key) { mutableListOf() } += content
        if (!dropNotifications) listeners.toList().forEach { it.onChanged(listOf(key)) }
    }

    fun reset() = listeners.toList().forEach { it.onReset() }

    override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> = start(key, null)

    override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> = start(
        key,
        known
    )

    private fun start(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> {
        val read = Read(key, known, events(), now(), versionOf(key))
        reads += read
        if (hold) pending += read else read.release(atStart = true)
        return read.future
    }

    override fun subscribe(listener: SnapshotChangeListener): AutoCloseable {
        listeners += listener
        duringSubscribe(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
}
