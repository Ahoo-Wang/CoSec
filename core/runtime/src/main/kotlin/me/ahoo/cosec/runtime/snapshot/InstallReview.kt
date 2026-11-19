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
 * housekeeping thread, never on a load's path. A review that housekeeping refuses (its queue full, `SNP-19`) is counted
 * as refused and offered again when a revalidation pass next visits the key, so a review is late at worst, never lost
 * while the document stays installed. What a review throws is caught and logged; it never touches the install.
 */
@InternalCoSecApi
fun interface InstallReview<in T : Any> {
    fun installed(key: SnapshotKey, revision: Long, compiled: T)
}
