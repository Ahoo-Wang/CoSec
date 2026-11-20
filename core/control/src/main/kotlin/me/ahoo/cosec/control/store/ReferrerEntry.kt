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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * One reference to a policy (RFC 0007 §5.8): the [referrer] document, the [revision] of it that indexed the reference,
 * and whether that revision defines the policy's id itself (RC1-9) rather than binding or naming the target's: the
 * platform's checks read it instead of the referrer's head (`CTL-43`).
 */
@ExperimentalCoSecApi
data class ReferrerEntry(val referrer: SnapshotKey, val revision: Long, val definesItself: Boolean = false) {
    init {
        require(revision >= 1) { "A referrer entry names a revision, at least 1." }
    }
}
