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

package me.ahoo.cosec.runtime.event

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import me.ahoo.cosec.annotations.InternalCoSecApi
import kotlin.time.Duration

private val log = KotlinLogging.logger(DecisionListeners::class.qualifiedName!!)

/**
 * Counts the events the BEST_EFFORT queues drop and logs them off the request's thread (#1392, OBS-3): [dropped] counts
 * and signals, without waiting or logging; [run], on the listeners' own dispatcher, logs for each listener how many it
 * dropped since its last line and why, then waits [interval] before it looks again. The signal is conflated, so a burst
 * of drops wakes it once and nothing piles up behind it.
 */
internal class DropReporter(private val stats: List<DecisionListeners.Stats>, private val interval: Duration) {
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** On the request's thread: one drop of [listener]'s, by a closed queue when [closed]. */
    fun dropped(listener: DecisionListeners.Stats, closed: Boolean) {
        listener.droppedCount.incrementAndGet()
        if (closed) {
            listener.closedDrops.incrementAndGet()
        }
        signal.trySend(Unit)
    }

    suspend fun run() {
        val reported = LongArray(stats.size)
        val reportedClosed = LongArray(stats.size)
        while (true) {
            signal.receive()
            stats.forEachIndexed { index, listener ->
                val all = listener.droppedCount.get()
                val closedDrops = listener.closedDrops.get()
                val afterClose = closedDrops - reportedClosed[index]
                val full = all - reported[index] - afterClose
                reported[index] = all
                reportedClosed[index] = closedDrops
                if (full > 0) {
                    warn {
                        "Dropping decision events: the queue of listener [${listener.listener}] is full; $full dropped since the last report."
                    }
                }
                if (afterClose > 0) {
                    warn {
                        "Dropping decision events: the queue of listener [${listener.listener}] is closed; $afterClose dropped since the last report."
                    }
                }
            }
            delay(interval)
        }
    }

    /** A failing appender must not end the reporter: its exception, and only that, is swallowed. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun warn(message: () -> String) {
        try {
            log.warn(message)
        } catch (_: RuntimeException) {
            // The appender failed; the drops stay counted in the stats.
        }
    }
}
