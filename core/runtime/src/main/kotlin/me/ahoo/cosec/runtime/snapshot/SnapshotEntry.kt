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
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.time.Instant

/**
 * One document as a [SnapshotSource] holds it. Immutable except for [confirmedAt], which revalidation moves forward
 * when the store confirms the same digest.
 */
@InternalCoSecApi
class SnapshotEntry<out T : Any> internal constructor(
    val key: SnapshotKey,
    val state: DocumentState<T>,
    /** The document's revision; 0 when [state] is not backed by a decoded document. */
    val revision: Long,
    internal val digest: SnapshotDigest?,
    confirmedAt: Instant,
    internal val origin: EntryOrigin,
) {
    @Volatile
    var confirmedAt: Instant = confirmedAt
        private set

    /** Whether the document is stored and does not compile on this instance: a decision says `Cause.DocumentInvalid`. */
    val invalid: Boolean get() = origin == EntryOrigin.UNCOMPILABLE

    /** A snapshot of this entry alone, built once so that requests needing no app document allocate nothing. */
    internal val alone: Snapshot<T, Nothing> = Snapshot(this, null)

    internal fun confirm(at: Instant) {
        if (at.isAfter(confirmedAt)) {
            confirmedAt = at
        }
    }

    fun lagAt(now: Instant): Duration = Duration.between(confirmedAt, now).coerceAtLeast(Duration.ZERO)

    override fun toString(): String = "SnapshotEntry(key=$key, state=$state, revision=$revision, confirmedAt=$confirmedAt)"
}
