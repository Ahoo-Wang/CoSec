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
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.time.Duration

/**
 * Events of a [SnapshotSource], for the `cosec.snapshot.*` meters (`cosec-micrometer`). Gauges (documents held, lag)
 * read the [SnapshotSummary] of the last lag pass ([summary]) instead, never [SnapshotSource.entries] on a scrape.
 *
 * [onLoad] runs on the request path, on whatever thread calls [SnapshotSource.peek], so implementations must be cheap,
 * allocate nothing and never block. Tag by [SnapshotKind], never by app or tenant id: those are unbounded. The source
 * wraps an implementation in [GuardedSnapshotMetrics]: what it throws never reaches a load.
 */
@InternalCoSecApi
interface SnapshotMetrics {
    /** One document a request needed: already installed ([LoadOutcome.HIT]) or loaded for it. */
    fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) = Unit

    fun onCompile(kind: SnapshotKind, duration: Duration, succeeded: Boolean) = Unit

    /** A new entry replaced the installed one, or filled an empty slot. */
    fun onInstall(entry: SnapshotEntry<*>) = Unit

    /**
     * The store answered with a revision lower than the last one installed under [key], or the same revision with
     * different content. The loaded document is installed anyway: the store is the record. In practice this means the
     * store lost an acknowledged write, as Redis does when a replica without it is promoted after a failover.
     */
    fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) = Unit

    /** The store answered `Absent` for a document that had been seen; the key now fails closed. */
    fun onDocumentLost(key: SnapshotKey, lastRevision: Long) = Unit

    /** A document's lag passed [SnapshotOptions.lagWarn], or [SnapshotOptions.lagEscalate] when [escalated]. */
    fun onLagExceeded(key: SnapshotKey, lag: Duration, escalated: Boolean) = Unit

    /**
     * The compile pool refused a key's drain hand-off (RFC 0013 B2b §3): it went to housekeeping, or, [ranInPlace], a
     * caller's thread ran the source's hand-offs, for as long as they came: the back-pressure that keeps answering.
     */
    fun onHandOffRefused(ranInPlace: Boolean) = Unit

    /**
     * A key of [kind] left its family by eviction (`SNP-7`): [absent] when what it held said the store has no document,
     * otherwise a document. Sustained, with cold loads rising, the family's bound is too small.
     */
    fun onEvicted(kind: SnapshotKind, absent: Boolean) = Unit

    companion object {
        val NOOP: SnapshotMetrics = object : SnapshotMetrics {}
    }
}

@InternalCoSecApi
enum class LoadOutcome {
    /** Already installed. */
    HIT,

    /** Not installed; loaded for this request. */
    COLD,

    /** Not installed; the load failed, and the request sees a failed document. */
    FAILED,

    /** Not installed; the load did not finish within [SnapshotOptions.coldLoadTimeout]. */
    TIMEOUT,
}
