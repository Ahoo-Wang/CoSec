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
 * What every [Slot] of a [SnapshotSource] reads from it (RFC 0013 B2a), named in one place, where the inner class it
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
    val pendingRequests: MutableSet<Slot<*>.RequestView> = ConcurrentHashMap.newKeySet()

    /** Set once, by [SnapshotSource.close]. */
    val closed = AtomicBoolean()

    fun closedError() = IllegalStateException("SnapshotSource is closed.")
}
