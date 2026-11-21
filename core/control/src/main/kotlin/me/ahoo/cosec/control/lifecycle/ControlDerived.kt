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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration

/**
 * The control plane's cadences and limits that are computed or constant, not configured (the rule of root `AGENTS.md`:
 * what can be computed is not configured). An operator states the evidence a change needs
 * (`cosec.control.shadow.min-duration`, `min-samples`, `max-duration`) and how much history is kept
 * (`cosec.control.revisions.max-count`, `min-age`); nothing below carries knowledge only an operator has.
 */
@InternalCoSecApi
object ControlDerived {
    /**
     * How often every instance reclaims revisions and expires open changes, each document under a lease. A constant:
     * what is reclaimed is decided by the retention the operator states, and this only decides how soon after a record
     * becomes eligible it goes. A pass over documents with nothing to reclaim costs one index read and one head read each.
     */
    val RECLAIM_INTERVAL: Duration = Duration.ofHours(1)

    /**
     * How old a record not on a revision chain, or a change listed but never written, is before it is removed: one
     * reclaim pass. A write in progress takes seconds, so a record that has outlived a whole pass is not one, and the
     * pass is the only thing that looks. It must not be shorter than [RECLAIM_INTERVAL]: a pass would then remove a record
     * whose first write is still in flight.
     */
    val ORPHAN_AGE: Duration = RECLAIM_INTERVAL

    /** The shadow pass looks at the open windows this many times within the least a window lives. */
    const val SHADOW_CHECKS_PER_MIN_DURATION: Int = 15

    /** The shadow pass is never more often than this: it takes a lease and reads every shadowing change. */
    val SHADOW_CHECK_FLOOR: Duration = Duration.ofMillis(250)

    /** ... and never less often than this: a window closes up to one interval late. */
    val SHADOW_CHECK_CEILING: Duration = Duration.ofMinutes(1)

    /**
     * How often shadowing changes are looked at, by one instance at a time: a [SHADOW_CHECKS_PER_MIN_DURATION]th of
     * [minDuration] (`cosec.control.shadow.min-duration`), the least a window lives, since a window cannot close earlier
     * and a window closes up to one interval late; within [SHADOW_CHECK_FLOOR] and [SHADOW_CHECK_CEILING]. A minute at
     * the default quarter of an hour.
     */
    fun shadowCheckInterval(minDuration: Duration): Duration =
        minDuration.dividedBy(
            SHADOW_CHECKS_PER_MIN_DURATION.toLong()
        ).coerceIn(SHADOW_CHECK_FLOOR, SHADOW_CHECK_CEILING)

    /** What a request body may hold besides the document: the operations' JSON around it. */
    const val BODY_ROOM_BYTES: Long = 2L * 1024 * 1024

    /**
     * The largest request body the control routes read: the document cap ([ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES]) and
     * [BODY_ROOM_BYTES], 10 MB. Larger is a 400 (`CTL-41`); raising the document cap raises it too.
     */
    const val MAX_BODY_BYTES: Long = ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong() + BODY_ROOM_BYTES

    /** One line for the startup log: what was derived, from what. */
    fun describe(minDuration: Duration): String =
        "reclaim-interval=$RECLAIM_INTERVAL (constant), orphan-age=$ORPHAN_AGE (one reclaim pass), " +
            "shadow check interval=${shadowCheckInterval(minDuration)} (1/$SHADOW_CHECKS_PER_MIN_DURATION of " +
            "cosec.control.shadow.min-duration $minDuration), max body=${MAX_BODY_BYTES / MEBIBYTE}MB " +
            "(the ${ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES / MEBIBYTE}MB document cap and ${BODY_ROOM_BYTES / MEBIBYTE}MB)"

    private const val MEBIBYTE = 1024L * 1024
}
