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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import java.time.Clock
import java.time.Instant

/**
 * The only way the outside reaches [KeyTransitions.next] (RFC 0013 B2b): filled by the runner as it takes an event.
 * [seen] is `families.seen[key]`, which only this key's runner writes, through [KeyEffect.RecordSeen].
 *
 * The wall-clock [instant] stamps what a transition records (a load's start, a failed entry) and decides nothing, so
 * the runner's moment reads its clock only when a transition first asks, once per step (RFC 0013 B2b step 3).
 */
internal class KeyMoment private constructor(
    private val clock: Clock?,
    private var read: Instant?,
    /** Monotonic nanoseconds, the time the scheduler keeps. */
    val nanos: Long,
    /** The source's compile epoch. */
    val epoch: Long,
    val closed: Boolean,
    val seen: Seen?,
) {
    /** A moment whose instant is known. */
    constructor(instant: Instant, nanos: Long, epoch: Long, closed: Boolean, seen: Seen?) :
        this(null, instant, nanos, epoch, closed, seen)

    /** The runner's: [clock] read the first time a transition needs the instant. */
    constructor(clock: Clock, nanos: Long, epoch: Long, closed: Boolean, seen: Seen?) :
        this(clock, null, nanos, epoch, closed, seen)

    val instant: Instant get() = read ?: clock!!.instant().also { read = it }

    /** This moment, closed: what [KeyEvent.Close]'s transition decides with. */
    fun closing(): KeyMoment = KeyMoment(clock, read, nanos, epoch, true, seen)
}
