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
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.time.Instant

// The entries KeyTransitions and KeyLoads make that no store answer stands behind, and when a compilation is outdated.

/** A family entry compiled in an epoch before [moment]'s: [KeyEvent.Recompile] may have replaced its inputs since. */
internal fun KeyConfig.compiledBefore(load: LoadState<*>, entry: SnapshotEntry<*>, moment: KeyMoment): Boolean =
    !shared && load.epoch < moment.epoch && entry.origin.compiled

/** A withheld compilation with no load to follow it (the key is closing) answers a failure. */
internal fun <T : Any> outdated(key: SnapshotKey, moment: KeyMoment): SnapshotEntry<T> =
    failedEntry(key, SnapshotUnavailableException(key, "compiled with inputs that changed"), moment.instant)

/** A failure of [key] as of [at] with nothing behind it: never installed. */
internal fun <T : Any> failedEntry(key: SnapshotKey, error: Throwable, at: Instant): SnapshotEntry<T> =
    SnapshotEntry(key, DocumentState.Failed(error), 0, null, at, EntryOrigin.TRANSIENT)

/** The store could not be read: [cause], as the failure a waiter gets. */
internal fun <T : Any> transientFailure(key: SnapshotKey, at: Instant, cause: Throwable): SnapshotEntry<T> {
    val error = cause as? SnapshotUnavailableException ?: SnapshotUnavailableException(key, "the store cannot be read", cause)
    return failedEntry(key, error, at)
}

/** An entry with no decoded document behind it: what the store answered, as of [load]'s confirmation time. */
internal fun <T : Any> KeyConfig.entryOf(
    key: SnapshotKey,
    load: LoadState<T>,
    state: DocumentState<T>,
    origin: EntryOrigin
): SnapshotEntry<T> =
    SnapshotEntry(key, state, 0, null, load.confirmedAt(localCopyMaxAge), origin)

/** An entry of what [read] answered, as of [confirmedAt]: its revision, digest and stored size (`SNP-24`). */
internal fun <T : Any> readEntry(
    key: SnapshotKey,
    state: DocumentState<T>,
    read: SnapshotRead.Present,
    confirmedAt: Instant,
    origin: EntryOrigin,
): SnapshotEntry<T> =
    SnapshotEntry(key, state, read.document.revision, read.digest, confirmedAt, origin, read.document.bodySize)

/** This entry failed as [error], keeping the document it was read from: its revision, digest, confirmation and size. */
internal fun <T : Any> SnapshotEntry<T>.failedAs(error: Throwable, origin: EntryOrigin): SnapshotEntry<T> =
    SnapshotEntry(key, DocumentState.Failed(error), revision, digest, confirmedAt, origin, storedBytes)
