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
import java.time.Duration

/** How long revision records are kept (RFC 0007 §5.7). */
@InternalCoSecApi
class RetentionPolicy(
    /** The most recent revisions along the chain that are always kept (`cosec.control.revisions.max-count`). */
    val maxCount: Int = 50,
    /** Revisions younger than this are always kept (`cosec.control.revisions.min-age`). */
    val minAge: Duration = Duration.ofDays(7),
    /** Records not on the chain are removed once this old (`cosec.control.revisions.orphan-age`). */
    val orphanAge: Duration = Duration.ofHours(1),
    /** How long one instance holds the reclamation lease of a document. */
    val leaseDuration: Duration = Duration.ofMinutes(5),
) {
    init {
        require(maxCount >= 1) { "At least the head is kept." }
        require(
            !minAge.isNegative && !orphanAge.isNegative && !leaseDuration.isNegative
        ) { "Durations are not negative." }
    }
}
