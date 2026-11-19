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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Told of each compiled document a [SnapshotSource] installs (UC-1): after the install has been handed over, on the
 * housekeeping thread, never on a load's path. Each install is offered, a forced reload or a recompile of the same
 * revision included; a revalidation that confirms what is installed installs nothing. A reviewer that wants one review
 * per revision remembers what it reviewed. A review that housekeeping refuses (its queue full, `SNP-19`) is counted as
 * refused and offered again when a later read finds the same revision still installed (a revalidation pass); an
 * install replaces it (a new revision is reviewed instead) or drops it (a failed or absent document has nothing to
 * review). So a review is late at worst, never lost while its revision stays installed. What a review throws is caught
 * and logged; it never touches the install.
 */
@InternalCoSecApi
fun interface InstallReview<in T : Any> {
    fun installed(key: SnapshotKey, revision: Long, compiled: T)

    /** [key] left the source (evicted, or dropped empty): what the review keeps for it can go. */
    fun removed(key: SnapshotKey) {}
}
