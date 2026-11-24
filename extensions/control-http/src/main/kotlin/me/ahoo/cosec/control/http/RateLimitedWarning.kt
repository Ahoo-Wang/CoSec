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
package me.ahoo.cosec.control.http

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/** A `WARN` given at most once an [interval], with how many it held back since the last one. */
internal class RateLimitedWarning(private val clock: Clock, private val interval: Duration = Duration.ofMinutes(1)) {
    private val warnedAt = AtomicReference<Instant?>(null)
    private val held = AtomicLong()

    fun warn(message: () -> String) {
        val now = clock.instant()
        val last = warnedAt.get()
        if (last != null && last.plus(interval).isAfter(now) || !warnedAt.compareAndSet(last, now)) {
            held.incrementAndGet()
            return
        }
        val before = held.getAndSet(0)
        log.warn { if (before == 0L) message() else "${message()}; $before more since the last warning" }
    }
}
