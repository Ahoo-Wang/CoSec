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
import me.ahoo.cosec.control.store.ControlStore
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
    }
}
