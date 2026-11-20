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
 * What each referrer's head refers to, read once for a whole [Revisions.pruneReferences] run: a document refers to many
 * policies, and reading its head for each would decode it that many times.
 */
@InternalCoSecApi
class ReferrerHeads {
    internal val read = HashMap<SnapshotKey, Referring>()

    /**
     * Whether [entry] of [policy] of [target] is gone: its referrer's head is at or above the revision that indexed it,
     * and has no such reference, with the same flag (`CTL-43`). An entry that says it refers where the head now defines
     * the id itself is gone; until a repair removes it, it errs towards blocking.
     */
    internal fun gone(entry: ReferrerEntry, target: SnapshotKey, policy: PolicyId): Boolean =
        read[entry.referrer]?.let {
            it.revision >= entry.revision && ReferrerIndex.Reference(target, policy, entry.definesItself) !in it.references
        } == true

    /** A head's [revision] and what it [references]; revision 0 for one that is not there or cannot be read. */
    internal class Referring(val revision: Long, val references: Set<ReferrerIndex.Reference>)
}
