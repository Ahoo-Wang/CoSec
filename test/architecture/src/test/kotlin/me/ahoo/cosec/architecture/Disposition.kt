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

package me.ahoo.cosec.architecture

/** What becomes of a structure ledger's entry: a batch removes it, or it stays for a reason that was reviewed. */
sealed interface Disposition {
    /** Removed by [batch]: the entry goes when that batch lands. */
    class Debt(val batch: Batch) : Disposition {
        override fun toString(): String = batch.name
    }

    /** Stays, for [reason]. */
    class Stays(val reason: String) : Disposition {
        init {
            require(reason.isNotBlank()) { "An entry that stays says why." }
        }

        override fun toString(): String = "stays: $reason"
    }
}
