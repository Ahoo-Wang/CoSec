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

package me.ahoo.cosec.runtime

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * At most one report per [interval] for each key: for a failure that repeats on every request, which one log line an
 * interval describes as well as one per request does, and which a client could otherwise turn into a log flood. The
 * keys are a bounded set the caller owns (a registered provider, the one generator), never a request's value.
 */
internal class Throttle(interval: Duration, private val nanoTime: () -> Long = System::nanoTime) {
    private val interval = interval.toNanos()
    private val last = ConcurrentHashMap<Any, AtomicLong>()

    /** Whether to report [key] now: true for its first report, then once [interval] has passed since the last. */
    fun allow(key: Any): Boolean {
        val now = nanoTime()
        val reported = last.computeIfAbsent(key) { AtomicLong(Long.MIN_VALUE) }
        val previous = reported.get()
        if (previous != Long.MIN_VALUE && now - previous < interval) {
            return false
        }
        return reported.compareAndSet(previous, now)
    }
}
