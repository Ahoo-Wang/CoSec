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

package me.ahoo.cosec.snapshot

import java.time.Duration
import java.time.Instant

/**
 * Compiles one snapshot document. Called once per stored revision, never on the caller's thread of
 * [SnapshotSource.load], and its result is shared by every request until a newer revision replaces it, so it must be
 * immutable.
 *
 * An entry that fails to compile should be recorded in the result (RFC 0003 §3.4 `Broken`) so that only the layers
 * reading it fail. Throwing fails the whole document closed.
 */
fun interface SnapshotCompiler<out T : Any> {
    fun compile(key: SnapshotKey, document: SnapshotDocument): T
}

/** The state of one document in a [SnapshotSource] (RFC 0003 §3.4). */
sealed interface DocumentState<out T : Any> {
    data class Present<out T : Any>(val compiled: T) : DocumentState<T>

    /** No document is stored, and none has been seen under this key since the source started. */
    data object Absent : DocumentState<Nothing>

    /** The document cannot be used. Every layer it feeds is unavailable, so evaluation reaching it fails closed. */
    data class Failed(val error: Throwable) : DocumentState<Nothing>
}

/**
 * One document as a [SnapshotSource] holds it. Immutable except for [confirmedAt], which revalidation moves forward
 * when the store confirms the same digest.
 */
class SnapshotEntry<out T : Any> internal constructor(
    val key: SnapshotKey,
    val state: DocumentState<T>,
    /** The document's revision; 0 when [state] is not backed by a decoded document. */
    val revision: Long,
    internal val digest: SnapshotDigest?,
    confirmedAt: Instant,
    /** Whether the store held no document when this entry was made: plain absence, or a document that was lost. */
    internal val storedAbsent: Boolean = false,
) {
    @Volatile
    var confirmedAt: Instant = confirmedAt
        private set

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

/**
 * The documents one evaluation reads: the shared [policies] document and, when the request needs it, one [app]
 * document. Every entry is immutable, so an evaluation sees exactly one revision of each document (RFC 0003 §8.4 P7).
 */
class Snapshot<out P : Any, out A : Any> internal constructor(
    val policiesEntry: SnapshotEntry<P>,
    /** Null when the request needs no app document. */
    val appEntry: SnapshotEntry<A>?,
) {
    val policies: DocumentState<P> get() = policiesEntry.state

    val app: DocumentState<A>? get() = appEntry?.state

    val version: SnapshotVersion get() = SnapshotVersion(policiesEntry.revision, appEntry?.revision ?: 0)

    /** The lag of the older of the documents (RFC 0003 §4.5). */
    fun lagAt(now: Instant): Duration {
        val policiesLag = policiesEntry.lagAt(now)
        val appLag = appEntry?.lagAt(now) ?: return policiesLag
        return maxOf(policiesLag, appLag)
    }

    override fun toString(): String = "Snapshot(policies=$policiesEntry, app=$appEntry)"
}
