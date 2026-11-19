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

import io.github.oshai.kotlinlogging.KotlinLogging
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
internal class KeyContext(
    val store: SnapshotDocumentStore,
    /** The document every request needs. */
    val sharedKey: SnapshotKey,
    val options: SnapshotOptions,
    val clock: Clock,
    val ticker: LongSupplier,
    val metrics: SnapshotMetrics,
    val threads: SnapshotThreads,
    val families: SnapshotFamilies<*>,
    /** How each key's runner is made (RFC 0013 B2b): [KeyRunner.Settings]' defaults in production. */
    val runners: KeyRunner.Settings,
    reviews: List<InstallReview<*>> = emptyList(),
) {
    /**
     * Who is told of each installed document (UC-1, [InstallReview]); none but the starter's. Each takes what this
     * source's compilers make, as [DocumentSnapshots.source]'s types hold; the cast only drops that type here.
     */
    @Suppress("UNCHECKED_CAST")
    val reviews: List<InstallReview<Any>> = reviews as List<InstallReview<Any>>

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
    fun current(key: SnapshotKey): KeyRunner<*> = families.slotFor(key, this)

    /**
     * [key] left its family: each review is told so on the housekeeping thread, after any review of it still queued there
     * (one thread, in order). A refused offer leaves the reviewer's entry, which its own bound covers.
     */
    fun reviewRemoved(key: SnapshotKey) {
        if (reviews.isEmpty()) {
            return
        }
        threads.offHousekeeping(HousekeepingTask.INSTALL_REVIEW, key) {
            for (review in reviews) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    review.removed(key)
                } catch (error: Exception) {
                    log.warn(error) { "A review failed to forget [$key]." }
                }
            }
        }
    }

    private companion object {
        // The source's logger, as KeyEffects': operators configure the one name.
        private val log = KotlinLogging.logger(SnapshotSource::class.java.name)
    }
}
