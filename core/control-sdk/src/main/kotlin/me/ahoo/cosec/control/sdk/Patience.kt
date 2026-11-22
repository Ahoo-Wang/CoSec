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

package me.ahoo.cosec.control.sdk

import kotlinx.coroutines.delay
import java.time.Duration

/** How long [ControlClient.proposeAndPublishWithoutShadow] keeps trying, and how long it waits between two tries. */
internal class Patience(
    /** How long the write keeps trying, and so how long a change it gave up on stays before it abandons it. */
    val duration: Duration,
    private val clock: () -> Long = System::nanoTime,
) {
    private val deadline = clock() + duration.toNanos()
    private var pause = FIRST_PAUSE_MILLIS

    /** Waits before the next try and answers true; false, without waiting, once the next try would come too late. */
    suspend fun next(): Boolean {
        val left = (deadline - clock()) / NANOS_PER_MILLI
        if (left < pause) return false
        delay(pause)
        pause = (pause * 2).coerceAtMost(LAST_PAUSE_MILLIS)
        return true
    }

    private companion object {
        const val FIRST_PAUSE_MILLIS = 25L
        const val LAST_PAUSE_MILLIS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
