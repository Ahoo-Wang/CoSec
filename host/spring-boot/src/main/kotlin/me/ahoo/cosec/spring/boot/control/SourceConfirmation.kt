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

package me.ahoo.cosec.spring.boot.control

import kotlinx.coroutines.future.await
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.DocumentConfirmation
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.SnapshotEntry
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Instant

/**
 * Confirms documents with a forced read of each from the store. A reload that fails keeps the installed revision and
 * completes normally, so the future alone does not say the store answered: every entry must have been confirmed at or
 * after this confirmation started, and must not be failed, or the check is indeterminate. A cold key may first be
 * answered from a local copy, dated up to the store's local-copy age before its read; it gets one more forced read,
 * which goes to the store of record. It answers the revision of each key as the source holds it now (`0` for none).
 */
internal class SourceConfirmation(
    private val source: SnapshotSource<*, *>,
    private val clock: Clock,
) : DocumentConfirmation {
    override suspend fun confirm(keys: Set<SnapshotKey>): Map<SnapshotKey, Any> {
        val start = clock.instant()
        val confirmed = HashMap<SnapshotKey, SnapshotEntry<*>>()
        val stale = keys.map { it to source.reload(it) }
            .mapNotNull { (key, reloading) ->
                val entry = reloading.await()
                confirmed[key] = entry
                key.takeUnless { entry.isConfirmedSince(start) }
            }
        if (stale.isNotEmpty()) {
            val retryStart = clock.instant()
            stale.map { it to source.reload(it) }.forEach { (key, reloading) ->
                val entry = reloading.await()
                check(entry.isConfirmedSince(retryStart)) {
                    "Snapshot document [${entry.key}] could not be confirmed current: $entry."
                }
                confirmed[key] = entry
            }
        }
        // A forced reload makes a new entry even for a document that did not change, so an entry is not its own version:
        // the control plane moves a document only to a higher revision, and the revision is what a batch compares.
        return confirmed.mapValues { (_, entry) -> entry.revision }
    }

    private fun SnapshotEntry<*>.isConfirmedSince(start: Instant): Boolean =
        state !is DocumentState.Failed && !confirmedAt.isBefore(start)
}
