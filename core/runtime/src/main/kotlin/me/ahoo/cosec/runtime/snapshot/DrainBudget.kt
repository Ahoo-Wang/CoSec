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
 * to. The compile pool and housekeeping drain without a bound, and so do close and a hand-off run in place. Kept in
 * one cell per thread, reused: a top-level offer allocates nothing.
 */
internal object DrainBudget {
    /** [ACTIVE]: whether a call on this thread set the budget; [LEFT]: the events it has left. */
    private val cell = ThreadLocal.withInitial { LongArray(2) }

    /** Runs [block] with [events] to spend, unless an outer call on this thread set the budget: then it spends that one. */
    internal inline fun <R> within(events: Int, block: () -> R): R {
        val cell = cell()
        if (cell[ACTIVE] != 0L) return block()
        cell[ACTIVE] = 1L
        cell[LEFT] = events.toLong()
        try {
            return block()
        } finally {
            cell[ACTIVE] = 0L
        }
    }

    /**
     * Runs [block] with no bound, whatever an outer call on this thread set, and restores it after: a hand-off run in
     * place, and close, which drains to empty.
     */
    internal inline fun <R> unbounded(block: () -> R): R {
        val cell = cell()
        val active = cell[ACTIVE]
        cell[ACTIVE] = 0L
        try {
            return block()
        } finally {
            cell[ACTIVE] = active
        }
    }

    /** Spends one event; false once the budget is spent. A thread outside any call has no bound. */
    fun spend(): Boolean {
        val cell = cell()
        if (cell[ACTIVE] == 0L) return true
        if (cell[LEFT] <= 0L) return false
        cell[LEFT]--
        return true
    }

    internal fun cell(): LongArray = cell.get()

    internal const val ACTIVE = 0
    internal const val LEFT = 1
}
