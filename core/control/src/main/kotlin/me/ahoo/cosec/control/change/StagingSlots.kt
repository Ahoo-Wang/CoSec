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

package me.ahoo.cosec.control.change

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * A document's staging slot (RFC 0007 §5.5) as [Changes] keeps it: who holds it, whether a holder still stages, and
 * releasing it with the candidate its holder staged.
 */
internal class StagingSlots(
    private val store: ControlStore,
    private val clock: Clock,
    private val options: ChangeOptions,
) {
    /** Whether [change] holds its document's slot. */
    suspend fun holds(change: ChangeRequest): Boolean = store.stage(change.key)?.value?.holder == change.id

    /** Whether [holder] still stages: in a staged state, and not past its expiry. */
    fun stillStaged(holder: ChangeRequest): Boolean =
        holder.state.staged && !clock.instant().isAfter(holder.createdAt.plus(options.expireAfter))

    /** The candidate [change] stages for its window [state]; 503 when it cannot be made. Nothing is reserved yet. */
    @Suppress("TooGenericExceptionCaught")
    fun candidate(change: ChangeRequest, state: ShadowState, applied: () -> PolicyDocument): StoredCandidate = try {
        val document = SnapshotDocument(
            PolicyDocumentCodec.SCHEMA,
            change.base.revision + 1,
            state.startedAt,
            PolicyDocumentCodec.encode(applied()),
        )
        StoredCandidate(state.window, change.base.revision, document)
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "the candidate of [${change.key}] could not be staged", e)
    }

    /**
     * Writes [candidate] for the change [shadowing] reserved. A write that fails or is cancelled gives the reservation
     * back ([giveBack], [undo]). A reservation lost during the write (the change was abandoned, expired or preempted)
     * takes its candidate with it: 409. A change is `SHADOWING` only while it holds the slot, with its candidate staged.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun write(shadowing: ChangeRequest, candidate: StoredCandidate, undo: suspend () -> Unit) {
        try {
            store.putCandidate(shadowing.key, candidate)
        } catch (e: CancellationException) {
            giveBack(shadowing, undo)
            throw e
        } catch (e: Exception) {
            giveBack(shadowing, undo)
            throw ChangeRejectedException(UNAVAILABLE, "the candidate of [${shadowing.key}] could not be staged", e)
        }
        confirm(shadowing)
    }

    /** That [shadowing] still holds its slot in its window; otherwise its candidate goes (409). */
    private suspend fun confirm(shadowing: ChangeRequest) {
        val current = store.change(shadowing.id)?.value
        if (current?.state != ChangeState.SHADOWING || current.shadow != shadowing.shadow || !holds(shadowing)) {
            withdraw(shadowing)
            throw ChangeRejectedException(CONFLICT, "change ${shadowing.id.value} lost its slot while it was staged")
        }
    }

    /**
     * Gives back what [reserved] holds, its candidate and its slot, and runs [undo], whether the caller is cancelled or
     * not. One that cannot be undone is left staged without a slot, and a publish finds the slot gone: it publishes nothing.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun giveBack(reserved: ChangeRequest, undo: suspend () -> Unit) = withContext(NonCancellable) {
        try {
            release(reserved)
            undo()
        } catch (e: Exception) {
            log.warn(e) { "Change ${reserved.id.value} did not get the slot and could not be made CHECKED again." }
        }
    }

    /** Releases the slot [change] holds, and withdraws its candidate. */
    suspend fun release(change: ChangeRequest) {
        withdraw(change)
        repeat(MAX_ATTEMPTS) {
            val current = store.stage(change.key) ?: return
            if (current.value.holder != change.id) {
                return
            }
            if (store.putStage(change.key, current.digest, StageSlot.FREE)) {
                return
            }
        }
    }

    /**
     * Withdraws the candidate of [change]'s window, if it is still the staged one: one conditional removal, since another
     * change may have taken the slot and staged its own meanwhile (#1227). A failure only leaves it to compare.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun withdraw(change: ChangeRequest) {
        val window = change.shadow?.window ?: return
        try {
            store.withdrawCandidate(change.key, window)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                e
            ) { "Withdrawing the candidate of window $window failed; instances count it as a base mismatch once it is stale." }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
        const val MAX_ATTEMPTS = 16
        const val CONFLICT = 409
        const val UNAVAILABLE = 503
    }
}
