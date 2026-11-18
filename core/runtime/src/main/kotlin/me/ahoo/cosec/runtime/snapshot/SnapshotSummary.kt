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
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.time.Duration
import java.time.Instant

/**
 * What a [SnapshotSource] held at the end of its last lag pass ([LagReports]), per [SnapshotKind]: the entries by
 * [State], the largest lag among those the lag reports read, and how many of them are past
 * [SnapshotOptions.lagWarn] and [SnapshotOptions.lagEscalate]. The pass already walks every entry; the gauges of
 * `cosec-micrometer` read this, never [SnapshotSource.entries] on a scrape. Before the first pass every number is 0 and
 * [takenAt] is null.
 */
@InternalCoSecApi
class SnapshotSummary internal constructor(
    /** When the pass that produced this ended; null before the first. */
    val takenAt: Instant?,
    private val kinds: Map<SnapshotKind, Kind>,
) {
    /** One kind's numbers. */
    @InternalCoSecApi
    class Kind internal constructor(
        private val held: IntArray,
        /** The largest lag among the entries the lag reports read (every entry but a failed one); zero for none. */
        val maxLag: Duration,
        /** Entries past [SnapshotOptions.lagWarn], those past [SnapshotOptions.lagEscalate] included. */
        val lagging: Int,
        /** Entries past [SnapshotOptions.lagEscalate]. */
        val escalated: Int,
    ) {
        /** How many entries of [state] are held. */
        fun held(state: State): Int = held[state.ordinal]
    }

    /** What an entry holds, one value per origin an installed entry can have: the states sum to the entries held. */
    @InternalCoSecApi
    enum class State {
        /** A document, compiled. */
        PRESENT,

        /** A stored document that does not compile here (a decision says `Cause.DocumentInvalid`). */
        INVALID,

        /** A document whose inputs changed and whose compile again has not run: failing closed until it does. */
        PENDING,

        /** A stored value that cannot be decoded. */
        UNDECODABLE,

        /** No document, and none seen before: bounded by `max-absent`, where a burst of unknown ids shows first. */
        ABSENT,

        /** No document, although one was seen: the store lost it, and the key fails closed. */
        LOST,

        /** The store could not be read. Never installed today: here so that every origin has a state. */
        UNREADABLE,
        ;

        internal companion object {
            fun of(origin: EntryOrigin): State = when (origin) {
                EntryOrigin.DOCUMENT -> PRESENT
                EntryOrigin.UNCOMPILABLE -> INVALID
                EntryOrigin.RECOMPILE_PENDING -> PENDING
                EntryOrigin.UNDECODABLE -> UNDECODABLE
                EntryOrigin.ABSENT -> ABSENT
                EntryOrigin.LOST -> LOST
                EntryOrigin.TRANSIENT -> UNREADABLE
            }
        }
    }

    /** The numbers of [kind]; zeros for a kind the source has none of. */
    fun of(kind: SnapshotKind): Kind = kinds[kind] ?: EMPTY_KIND

    /** What one lag pass counts as it walks the entries ([LagReports.report]). */
    internal class Tally {
        private val held = Array(SnapshotKind.entries.size) { IntArray(State.entries.size) }
        private val maxLag = Array<Duration>(SnapshotKind.entries.size) { Duration.ZERO }
        private val lagging = IntArray(SnapshotKind.entries.size)
        private val escalated = IntArray(SnapshotKind.entries.size)

        fun held(entry: SnapshotEntry<*>) {
            held[entry.key.kind.ordinal][State.of(entry.origin).ordinal]++
        }

        fun lag(entry: SnapshotEntry<*>, lag: Duration, lagging: Boolean, escalated: Boolean) {
            val kind = entry.key.kind.ordinal
            if (lag > maxLag[kind]) maxLag[kind] = lag
            if (lagging) this.lagging[kind]++
            if (escalated) this.escalated[kind]++
        }

        fun summary(at: Instant): SnapshotSummary = SnapshotSummary(
            at,
            SnapshotKind.entries.associateWith {
                Kind(held[it.ordinal], maxLag[it.ordinal], lagging[it.ordinal], escalated[it.ordinal])
            },
        )
    }

    internal companion object {
        private val EMPTY_KIND = Kind(IntArray(State.entries.size), Duration.ZERO, 0, 0)
        val EMPTY = SnapshotSummary(null, emptyMap())
    }
}

/** The summary of the last lag pass of this source: what its gauges read (`cosec-micrometer`). */
@InternalCoSecApi
fun SnapshotSource<*, *>.summary(): SnapshotSummary = lagReports.summary
