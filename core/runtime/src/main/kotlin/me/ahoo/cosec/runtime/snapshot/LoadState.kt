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
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import java.time.Duration
import java.time.Instant

/** The load in flight: one read of the store and what follows it. Ends exactly once, when [KeyState.load] changes. */
internal data class LoadState<T : Any>(
    val id: Long,
    val known: SnapshotEntry<T>?,
    val force: Boolean,
    /** The compile epoch this load started in: it compiles with the inputs of that epoch or a later one. */
    val epoch: Long,
    /** Whether [known] was compiled in an earlier epoch: this load reads in full and must not keep it. */
    val recompiles: Boolean,
    /** The read of the periodic pass that started this load, ended once its read answers or the load ends. */
    val pass: PassTicket?,
    val startedAt: Instant,
    val startedNanos: Long,
    /** Whether the read may be answered from a local copy rather than the store of record. */
    val fromLocalCopy: Boolean,
    /** The digest the conditional read sent; null for an unconditional read or "I believe it absent". */
    val askedDigest: SnapshotDigest?,
    val phase: LoadPhase,
    /** What this load's waiters hang on. */
    val handle: KeyHandle<T>,
    /** The handles of earlier loads whose compilation was withheld, answered with what this load ends with. */
    val alsoAnswers: List<KeyHandle<T>> = emptyList(),
) {
    /**
     * When what this load read is known to have been current: the start of the read, or for a local copy the oldest
     * that copy can be, so that only a conditional read can make it look fresh.
     */
    fun confirmedAt(
        localCopyMaxAge: Duration
    ): Instant = if (fromLocalCopy) startedAt.minus(localCopyMaxAge) else startedAt
}
