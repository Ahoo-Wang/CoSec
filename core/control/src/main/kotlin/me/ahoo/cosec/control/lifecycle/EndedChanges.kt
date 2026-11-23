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

package me.ahoo.cosec.control.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.EndedChange
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * How many ended change records a document keeps (`CTL-83`, DR1-E-2): a change's record outlives its end only for its
 * readers (the change's own `GET`, a command that answers it is over), and nothing else removed it, so a loop of
 * proposals grew the store without bound.
 *
 * - **The bound, on the write path.** After a change of a document ends, at most [maxCount] of the document's ended
 *   changes stay (`cosec.control.revisions.max-count`, as the open ones, [OpenLimit]): the oldest go first, whatever
 *   their age. A removal that fails warns, and reclamation removes it later.
 * - **Reclamation.** The pass removes the ended changes older than `min-age` that published no revision still
 *   retained: the record of a retained revision's change stays while the revision does, as long as the bound allows.
 *
 * A retained revision whose change record is gone is still explained: `explain` reads the revision, and the revision
 * names its author, approver and change in its own provenance. Only listed changes are removed
 * ([ControlStore.endedChanges]): a change that ended before the store kept the list stays.
 */
internal class EndedChanges(
    private val store: ControlStore,
    private val maxCount: Int,
    /** The changes' own clock, which wrote when each ended. */
    private val clock: Clock = Clock.systemUTC(),
) {
    /** After a change of [key] ended: the oldest ended changes past [maxCount] go. Never fails the write it follows. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun bound(key: SnapshotKey) {
        try {
            val listed = store.endedChanges(key, Instant.MAX, Int.MAX_VALUE)
            listed.take((listed.size - maxCount).coerceAtLeast(0)).forEach { remove(key, it) { true } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "The ended changes of [$key] could not be bounded now; reclamation removes them later." }
        }
    }

    /**
     * Removes the changes of [key] that ended more than [minAge] ago and published no revision in [retained]: at most
     * twice [maxCount] a pass, which the bound keeps above what is listed. The number removed.
     */
    suspend fun reclaim(key: SnapshotKey, minAge: Duration, retained: Set<Long>): Int =
        store.endedChanges(key, clock.instant().minus(minAge), maxCount * 2).count { ended ->
            remove(key, ended) { change -> change.publishedRevision?.let { it in retained } != true }
        }

    /**
     * Removes the record of [ended] when it is terminal and [removable]; one whose record is gone leaves the list. A
     * record that does not read, or is not terminal (its terminal write lost), stays: whether it removed one.
     */
    private suspend fun remove(
        key: SnapshotKey,
        ended: EndedChange,
        removable: (ChangeRequest) -> Boolean,
    ): Boolean {
        val stored = try {
            store.change(ended.id)
        } catch (_: SnapshotFormatException) {
            return false
        } ?: return store.removeChange(key, ended.id, null)
        val change = stored.value
        if (!change.state.terminal || change.key != key || !removable(change)) return false
        return store.removeChange(key, ended.id, stored.digest)
    }
}
