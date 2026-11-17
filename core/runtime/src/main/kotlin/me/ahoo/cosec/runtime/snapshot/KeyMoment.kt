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
import java.time.Instant

/**
 * The only way the outside reaches [KeyTransitions.next] (RFC 0013 B2b): filled by the runner as it takes an event.
 * [seen] is `families.seen[key]`, which only this key's runner writes, through [KeyEffect.RecordSeen].
 */
internal data class KeyMoment(
    val instant: Instant,
    /** Monotonic nanoseconds, the time the scheduler keeps. */
    val nanos: Long,
    /** The source's compile epoch. */
    val epoch: Long,
    val closed: Boolean,
    val seen: Seen?,
)
