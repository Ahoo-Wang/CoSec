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

import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Says yes at most once per [interval] of [timeSource], first at once; safe across threads. */
internal class Throttle(private val interval: Duration, private val timeSource: TimeSource = TimeSource.Monotonic) {
    private val last = AtomicReference<TimeMark?>(null)

    fun ready(): Boolean {
        val mark = last.get()
        return (mark == null || mark.elapsedNow() >= interval) && last.compareAndSet(mark, timeSource.markNow())
    }
}
