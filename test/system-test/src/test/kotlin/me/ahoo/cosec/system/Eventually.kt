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

package me.ahoo.cosec.system

import java.time.Duration

/** How often [eventually] asks again. A polling period, not a wait for anything: the deadline is the [Bound]. */
private val POLL: Duration = Duration.ofMillis(200)

/**
 * Asks [probe] until [done] accepts its answer, and returns that answer; fails, naming the [bound], when the bound's
 * deadline passes first. Each answer is in the transcript (the probe's requests are), so a failure shows every step.
 */
fun <T> eventually(bound: Bound, what: String, probe: () -> T, done: (T) -> Boolean): T {
    val deadline = System.nanoTime() + bound.duration.toNanos()
    var last: T
    while (true) {
        last = probe()
        if (done(last)) {
            Transcript.note("converged: $what")
            return last
        }
        if (System.nanoTime() >= deadline) {
            break
        }
        Thread.sleep(POLL.toMillis())
    }
    throw AssertionError("Waited $bound for $what; the last answer was: $last")
}
