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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The referrers a message names (R2C-1): the first [SHOWN], sorted, and how many there are in all. A policy every
 * tenant refers to has tens of thousands of referrers, and a finding is stored in the change record and returned by
 * every read of it, so it never carries them all.
 */
@InternalCoSecApi
object ReferrerNames {
    const val SHOWN = 20

    /** `[tenant:a, tenant:b]`, or `[the first 20] and 49980 more (50000 in all)`. */
    fun of(referrers: Collection<SnapshotKey>): String {
        val sorted = referrers.map { it.toString() }.sorted()
        if (sorted.size <= SHOWN) {
            return sorted.toString()
        }
        return "${sorted.take(SHOWN)} and ${sorted.size - SHOWN} more (${sorted.size} in all)"
    }

    /** Each policy, in id order, with its referrers as [of] names them. */
    fun of(byPolicy: Map<PolicyId, Set<SnapshotKey>>): String =
        byPolicy.entries.sortedBy { it.key.value }.joinToString("; ") { (policy, referrers) ->
            "[${policy.value}] by ${of(referrers)}"
        }
}
