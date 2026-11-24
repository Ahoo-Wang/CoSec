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
 * The revisions [key]'s index lists above its head (`CTL-86`, DR1-F-7): each was published, its record and index entry
 * written, and then its head write lost, as a Redis failover with asynchronous replication loses it. The head is back
 * at an older revision, and what that revision's change narrowed (a revoked binding) is in effect again. Empty when the
 * index or the head is missing: a document never published, or one whose history is rebuilt from its head.
 *
 * The index is read before the head, and the order bears the weight: a publish writes its head before its index entry,
 * so an entry the index read lists was written after its head, which the later read of the head then sees. Read the
 * other way round, a publish landing between the two reads would list its new revision above the head read before it,
 * and be reported lost when nothing was.
 */
@InternalCoSecApi
suspend fun ControlStore.lostRevisions(key: SnapshotKey): List<RevisionEntry> {
    // The index first, then the head: see above.
    val index = index(key)?.value ?: return emptyList()
    val head = head(key) ?: return emptyList()
    return index.entries.filter { it.revision > head.document.revision }
}
