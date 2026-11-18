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
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier

/**
 * What every key of a [SnapshotSource] reads from it (RFC 0013 B2a), named in one place, where the inner class it
 * was hid which of the source's fields it used. Every field is a `val`, set before the first slot is made; what changes
 * lives in the object a field holds, with one home each.
 */
internal class SlotContext(
    val store: SnapshotDocumentStore,
    /** The document every request needs. */
    val sharedKey: SnapshotKey,
    val options: SnapshotOptions,
    val clock: Clock,
    val ticker: LongSupplier,
    val metrics: SnapshotMetrics,
    val threads: SnapshotThreads,
    val families: SnapshotFamilies<*>,
    /** What makes each key's slot (RFC 0013 B2b): [SlotFactory.RUNNER] in production. */
    val slots: SlotFactory,
) {
    /**
     * Monotonic nanoseconds, the time the scheduler's timeouts keep: whether a periodic pass stalled is measured in it,
     * so a step of the wall clock does not look like time passing. Replaced only in tests, through
     * [SnapshotSource.nanoTime], and read here each time.
     */
    val nanoTime = AtomicReference(LongSupplier(System::nanoTime))

    /**
     * Bumped by [SnapshotSource.recompile]. A family slot remembers the epoch its entry was compiled in; a load that
     * starts in a later epoch compiles it again.
     */
    val compileEpoch = AtomicLong()

    /** The requests waiting on a slot, answered when the source closes. */
    val pendingRequests: MutableSet<PendingRequest> = ConcurrentHashMap.newKeySet()

    /** Set once, by [SnapshotSource.close]. */
    val closed = AtomicBoolean()

    /** The drains this source's keys hand off: one task in the compile pool at a time (RFC 0013 B2b §3). */
    val handOffs = DrainHandOffs(threads, metrics, closed)

    fun closedError() = IllegalStateException("SnapshotSource is closed.")

    /**
     * The runners' transitions (RFC 0013 B2b step 3): they keep no state, and each step carries its key, so a source
     * needs two, the shared key's (never recompiled, never removed) and every family key's.
     */
    private val sharedTransitions = KeyTransitions<Any>(keyConfig(shared = true))
    private val familyTransitions = KeyTransitions<Any>(keyConfig(shared = false))

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> transitions(shared: Boolean): KeyTransitions<T> =
        (if (shared) sharedTransitions else familyTransitions) as KeyTransitions<T>

    private fun keyConfig(shared: Boolean) = KeyConfig(
        key = null,
        shared = shared,
        storeReadTimeout = options.storeReadTimeout,
        rejectedReloadRetry = options.rejectedReloadRetry,
        revalidateInterval = options.revalidateInterval,
        localCopyMaxAge = store.localCopyMaxAge,
    )

    /**
     * The slot [families] holds for [key] now, made if it holds none: where a removed slot sends a request. Nothing it
     * reads is set later than this context's construction, and no slot exists before that.
     */
    fun current(key: SnapshotKey): KeySlot<*> = families.slotFor(key, this)
}
