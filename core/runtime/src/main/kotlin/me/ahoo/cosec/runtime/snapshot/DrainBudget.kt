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

/**
 * The events one top-level call may drain on its thread (RFC 0013 B2b §3, amendment 7): K + 1, spent by every event the
 * thread takes on any runner, nested drains included, so that a caller's thread does bounded work whatever it offers
 * to. CoSec's own pool threads and the closing thread drain without a bound. Kept per thread, set by the outermost call.
 */
internal object DrainBudget {
    private val left = ThreadLocal<IntArray?>()

    /** Runs [block] with [events] to spend, unless an outer call on this thread set the budget: then it spends that one. */
    inline fun <R> within(events: Int, block: () -> R): R {
        if (isSet()) return block()
        set(events)
        try {
            return block()
        } finally {
            clear()
        }
    }

    /** Spends one event; false once the budget is spent. A thread outside any call has no bound. */
    fun spend(): Boolean {
        val cell = left.get() ?: return true
        if (cell[0] <= 0) return false
        cell[0]--
        return true
    }

    @PublishedApi
    internal fun isSet(): Boolean = left.get() != null

    @PublishedApi
    internal fun set(events: Int) = left.set(intArrayOf(events))

    @PublishedApi
    internal fun clear() = left.remove()
}
