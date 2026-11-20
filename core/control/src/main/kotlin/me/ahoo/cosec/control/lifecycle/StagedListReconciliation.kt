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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Brings the staged list back to a superset of what is staged (`CTL-46`), once per shadow pass under its lease: what a
 * failed write or a crash between a stage's two steps left. It makes the list kept, lists the window of every shadowing
 * change whose candidate is written and not listed, and unlists a window whose document stages no candidate of it only
 * when the previous pass saw it so too: a stage lists its window before it writes its candidate, so a window can be
 * listed and its candidate still on its way for a moment, never for a whole pass.
 */
internal class StagedListReconciliation(private val store: ControlStore) {
    /** The windows the previous pass found listed with no candidate: unlisted if this pass finds them so again. */
    private var suspects: Set<Pair<SnapshotKey, String>> = emptySet()

    suspend fun reconcile(shadowing: List<ChangeRequest>) {
        store.keepStagedList()
        val listed = store.staged()?.windows ?: return
        for (change in shadowing) {
            val window = change.shadow?.window ?: continue
            if (listed[change.key]?.contains(window) != true && stagedWindow(change.key) == window) {
                store.listStaged(change.key, window)
            }
        }
        val stale = buildSet {
            for ((key, windows) in listed) {
                val staged = stagedWindow(key)
                windows.filterNot { it == staged }.forEach { add(key to it) }
            }
        }
        val unlisted = stale.intersect(suspects)
        unlisted.forEach { (key, window) -> store.unlistStaged(key, window) }
        suspects = stale - unlisted
    }

    private suspend fun stagedWindow(key: SnapshotKey): String? = store.stagedCandidate(key)?.value?.window
}
