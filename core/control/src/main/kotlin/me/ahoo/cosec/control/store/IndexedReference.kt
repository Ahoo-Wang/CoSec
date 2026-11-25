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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** A reference a document indexed: [policy] of [target], as [ReferrerEntry] of that document at [revision]. */
@InternalCoSecApi
data class IndexedReference(
    val target: SnapshotKey,
    val policy: PolicyId,
    val revision: Long,
    val definesItself: Boolean = false,
) {
    init {
        require(revision >= 1) { "An indexed reference names a revision, at least 1." }
    }

    /** The entry this reference is in the references to [policy] of [target], for [referrer]. */
    fun entryOf(referrer: SnapshotKey): ReferrerEntry = ReferrerEntry(referrer, revision, definesItself)
}
