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


import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * SPI (RFC 0003 §4.2): reads snapshot documents and reports changes. It neither compiles nor caches compiled results;
 * `SnapshotSource` does both.
 *
 * Every method must return without blocking and must never throw: failures complete the future exceptionally.
 *
 * A future that fails because the stored value exists but cannot be decoded (a foreign format, corrupt JSON, an
 * unknown [SnapshotDocument.schema]) must carry a [SnapshotFormatException] in its cause chain. Any other failure means
 * the read itself failed (the store is unreachable), and `SnapshotSource` keeps an installed document in that case
 * (RFC 0003 §4.4 F6) while it installs the failure for an undecodable one (F3, F5).
 */
interface SnapshotDocumentStore {
    /**
     * Reads [key], possibly from a local copy. Completes with [SnapshotRead.Present] or [SnapshotRead.Absent], never
     * [SnapshotRead.Unchanged]. Used for cold loads.
     */
    fun read(key: SnapshotKey): CompletableFuture<SnapshotRead>

    /**
     * Conditional read of the store of record, bypassing every local copy (RFC 0003 §4.5, Q5). When the stored value's
     * digest equals [known], completes with [SnapshotRead.Unchanged] and transfers no body. Otherwise completes with
     * [SnapshotRead.Present] or [SnapshotRead.Absent]. With `known = null` the store may answer either `Absent` or
     * `Unchanged` for a missing value. Used to revalidate installed documents, so its answer bounds the lag.
     */
    fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead>

    /**
     * The oldest a document answered by [read] can be when it comes from a local copy (CoCache's `localMaxAge` for
     * L2), or zero when [read] always answers from the store of record. `SnapshotSource` counts such a document as
     * confirmed this long before the read, so that only a [readIfChanged] answer can make it look fresh.
     */
    val localCopyMaxAge: Duration get() = Duration.ZERO

    /**
     * Subscribes [listener]. Closing the returned handle unsubscribes. Callbacks must not be delivered while the
     * store holds a lock the listener could need, and must be delivered after the change is readable.
     */
    fun subscribe(listener: SnapshotChangeListener): AutoCloseable
}

/** The answer to a [SnapshotDocumentStore] read. */
sealed interface SnapshotRead {
    /** The stored digest equals the one the caller knows. Only [SnapshotDocumentStore.readIfChanged] answers this. */
    data object Unchanged : SnapshotRead

    /** No document is stored under the key. */
    data object Absent : SnapshotRead

    class Present(val document: SnapshotDocument, val digest: SnapshotDigest) : SnapshotRead {
        override fun toString(): String = "Present(revision=${document.revision}, digest=$digest)"
    }
}

/** Change notifications from a [SnapshotDocumentStore]. Implementations return quickly and never block. */
interface SnapshotChangeListener {
    /** The documents under [keys] may have changed. */
    fun onChanged(keys: Collection<SnapshotKey>)

    /** Notifications may have been lost, for example while the subscription was being re-established. */
    fun onReset()
}
