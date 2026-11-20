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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * Brings the staged list back to a superset of what is staged (`CTL-46`), once per shadow pass under its lease: what a
 * failed write or a crash between two steps left. It makes the list kept, lists the window of every shadowing change
 * whose candidate is written and not listed, and settles a listed window when the previous pass found it so too:
 * - a window whose document stages no candidate of it is unlisted;
 * - a window whose candidate is staged while no shadowing change names it, a withdrawal that failed or a crash before it
 *   (`R2C-15`), has its candidate withdrawn, then is unlisted. Only a pass that read every open change judges this: a
 *   change it could not read may be the one shadowing.
 *
 * Two passes, because a stage lists its window, becomes shadowing and writes its candidate in that order within one call:
 * a window can be listed with its candidate on its way, or written by a change this pass did not see shadowing, for a
 * moment, never for a whole pass. The withdrawal is conditional on the window, so it never takes another window's candidate.
 */
internal class StagedListReconciliation(private val store: ControlStore) {
    /** The windows the previous pass found to settle: settled if this pass finds them so again. */
    private var suspects: Set<Pair<SnapshotKey, String>> = emptySet()

    /** Reconciles with [shadowing], the shadowing changes of this pass; [complete] when it read every open change. */
    suspend fun reconcile(shadowing: List<ChangeRequest>, complete: Boolean) {
        store.keepStagedList()
        val listed = store.staged()?.windows ?: return
        val live = shadowing.mapNotNullTo(HashSet()) { change -> change.shadow?.window?.let { change.key to it } }
        for ((key, window) in live) {
            if (listed[key]?.contains(window) != true && stagedWindow(key) == window) {
                store.listStaged(key, window)
            }
        }
        val found = buildSet {
            for ((key, windows) in listed) {
                val staged = stagedWindow(key)
                windows.filter { it != staged || (complete && (key to it) !in live) }.forEach { add(key to it) }
            }
        }
        val due = found.intersect(suspects)
        due.forEach { (key, window) -> settle(key, window) }
        suspects = found - due
    }

    /** Withdraws [window]'s candidate if it is still staged, and unlists the window once it is not. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun settle(key: SnapshotKey, window: String) = try {
        store.withdrawCandidate(key, window)
        store.unlistStaged(key, window)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Could not settle the staged window $window of [$key]; the next pass tries again." }
    }

    private suspend fun stagedWindow(key: SnapshotKey): String? = store.stagedCandidate(key)?.value?.window

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
