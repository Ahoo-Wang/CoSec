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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.CompletableFuture
import java.util.function.LongSupplier

/**
 * One key of a [SnapshotSource], as its callers see it (RFC 0013 B2b, step 2a). What `peek` reads and writes on every
 * request is final here, on one class whatever implements the rest, so it stays a field access the JIT inlines: the
 * installed entry, the removed flag and the LRU stamp. Everything else is asynchronous: an answer is a future, and no
 * request is ever null. [KeyRunner] implements it.
 */
internal abstract class KeySlot<T : Any>(val key: SnapshotKey, private val ticker: LongSupplier) {
    /** What `peek` serves; written by the implementation alone. */
    @Volatile
    var installed: SnapshotEntry<T>? = null
        protected set

    /**
     * Set when the key leaves its family's map (evicted or dropped): its entry is never served again, and its requests
     * go to the key's current slot.
     */
    @Volatile
    var removed = false
        protected set

    /**
     * The LRU stamp. A plain field: every hit writes it, and eviction only needs an approximate order, so it does
     * without the memory barrier a volatile write would cost on the hot path.
     */
    var lastAccess: Long = ticker.asLong
        private set

    fun touch() {
        lastAccess = ticker.asLong
    }

    /**
     * A hint: whether no load was in flight when asked. An eviction pass uses it to choose; the removal is what checks,
     * on the true state, and refuses a key that is loading by then.
     */
    abstract fun idle(): Boolean

    /** Tests only: nothing of this key in motion: no load in flight, and for a runner no event queued, no drain. */
    fun quiescent(): Boolean = idle() && !draining

    /** Tests only: an event queued or a drain under way. */
    protected abstract val draining: Boolean

    /** What one request waits for: the installed entry, or its own view of the load, bounded by the cold load timeout. */
    abstract fun request(): CompletableFuture<SnapshotEntry<T>>

    /** The installed entry if it has not failed, otherwise the load in flight or a new one. */
    abstract fun usable(): CompletableFuture<SnapshotEntry<T>>

    /** A load that starts after this call, in full when [force] (`SNP-13`); a removed key's goes to its current slot. */
    abstract fun reload(force: Boolean): CompletableFuture<SnapshotEntry<T>>

    /** As [reload] for a key still held; a removed one is [Reloaded.Skipped]: revalidation, reset, recompile, retries. */
    abstract fun reloadIfHeld(force: Boolean): CompletableFuture<Reloaded<T>>

    /**
     * The load in flight, or a new one, counted as a read of [pass]. Skipped for a removed key, and for a load in flight
     * longer than the store read timeout, which the pass does not wait for (`SNP-6`).
     */
    abstract fun load(pass: Pass): CompletableFuture<Reloaded<T>>

    /** A change notification: reload if this key is installed or loading. */
    abstract fun changed()

    /** After the compile epoch moved: whether this key is to be loaded again (loading, or idle with a compiled entry). */
    abstract fun markRecompile(): CompletableFuture<Boolean>

    /**
     * Removes this key if it is idle and, with [onlyIfEmpty], holds nothing. An eviction names the kind it chose the key
     * for ([claimedAbsent]): refused when the key is of the other kind by now, and at the bound when the family's claim
     * fails (`SNP-14`).
     */
    abstract fun evict(onlyIfEmpty: Boolean, claimedAbsent: Boolean?): CompletableFuture<Eviction>

    /** The source is closing: the load in flight ends, answering its waiters with what is installed or a failure. */
    abstract fun close()
}
