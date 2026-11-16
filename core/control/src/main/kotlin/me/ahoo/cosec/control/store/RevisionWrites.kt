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
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The conditional replaces [Revisions] and its parts make: of a document's revision index, of the app list and of a
 * policy's back-references. Each reads, changes and writes with the digest it read, again while that changed, at most
 * [maxAttempts] times; `false` when it kept changing.
 */
internal class RevisionWrites(private val store: ControlStore, private val maxAttempts: Int) {
    suspend fun updateIndex(key: SnapshotKey, change: (RevisionIndex) -> RevisionIndex): Boolean {
        repeat(maxAttempts) {
            val current = store.index(key)
            if (store.putIndex(key, current?.digest, change(current?.value ?: RevisionIndex.EMPTY))) {
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

    suspend fun updateReferrers(
        target: SnapshotKey,
        policy: PolicyId,
        change: (Map<SnapshotKey, Long>) -> Map<SnapshotKey, Long>,
    ): Boolean {
        repeat(maxAttempts) {
            val current = store.referrers(target, policy)
            val next = change(current?.value.orEmpty())
            if (next == (current?.value ?: emptyMap<SnapshotKey, Long>())) {
                return true
            }
            if (store.putReferrers(target, policy, current?.digest, next)) {
                return true
            }
        }
        return false
    }
}
