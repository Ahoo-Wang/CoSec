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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The conditional replaces [Revisions] and its parts make: of a document's revision index and of the app list. The
 * back-references are entries of their own, added and removed one by one ([ControlStore.addReferrer]), with no
 * replace. Each reads, changes and writes with the digest it read, again while that changed, at most [maxAttempts]
 * times; `false` when it kept changing.
 */
internal class RevisionWrites(private val store: ControlStore, private val maxAttempts: Int) {
    /** A conditional replace of [key]'s revision index, retried while it changes; no write when [change] leaves it as it is. */
    suspend fun updateIndex(key: SnapshotKey, change: (RevisionIndex) -> RevisionIndex): Boolean {
        repeat(maxAttempts) {
            val current = store.index(key)
            val next = change(current?.value ?: RevisionIndex.EMPTY)
            // An index that would be written as it is needs no write (a reclamation pass that reclaims nothing), and
            // an absent one stays absent: the first write is the one that lists the document.
            if (current != null && next == current.value) {
                return true
            }
            if (store.putIndex(key, current?.digest, next)) {
                return true
            }
        }
        return false
    }

    /** A conditional replace of the app list, retried while it changes; no write when [change] leaves it as it is. */
    suspend fun updateApps(change: (AppList) -> AppList): Boolean {
        repeat(maxAttempts) {
            val current = store.apps()
            val next = change(current?.value ?: AppList.EMPTY)
            if (next == (current?.value ?: AppList.EMPTY)) {
                return true
            }
            if (store.putApps(current?.digest, next)) {
                return true
            }
        }
        return false
    }
}
