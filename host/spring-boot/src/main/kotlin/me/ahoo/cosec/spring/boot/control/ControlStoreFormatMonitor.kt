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

package me.ahoo.cosec.spring.boot.control

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.StartupTask
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Whether the control store is still in the format before 8.0.0-beta.5 (the old document list is there), [read] every
 * [interval] from startup, on a thread of its own: what [ControlStoreFormatHealthIndicator] reports, from [status], which
 * never reads Redis. Logged at ERROR, naming `cosec-migrate repair-references`, when it becomes so: at startup, not on
 * every reading. It never rewrites the store itself; until the repair runs, a back-reference value of the old format
 * fails closed where a change touches it (`CTL-42`).
 *
 * A [StartupTask] (`STA-3`): it must read from startup on, not from the first health probe.
 */
@InternalCoSecApi
class ControlStoreFormatMonitor internal constructor(
    private val read: () -> Boolean,
    private val interval: Duration,
) : AutoCloseable, StartupTask {
    /** What the health indicator's `format` detail says, and whether the store needs the repair. */
    @InternalCoSecApi
    enum class Status(val detail: String, val down: Boolean) {
        PENDING("pending", false),
        CURRENT("8.0.0-beta.5", false),
        UNREPAIRED("written before 8.0.0-beta.5: run cosec-migrate repair-references", true),
        UNKNOWN("unknown", false),
    }

    private val log = KotlinLogging.logger {}

    @Volatile
    private var current: Status = Status.PENDING
    private var executor: ScheduledExecutorService? = null

    /** What is known now of the store's format, from the last reading. */
    fun status(): Status = current

    /** Reads every [interval] from now until [close]. */
    fun start(): ControlStoreFormatMonitor = apply {
        executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "cosec-control-store-format").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS) }
    }

    /** Reads the format now. A read that throws is a format that cannot be told, and the next one is still made. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun refresh() {
        val status = try {
            if (read()) Status.UNREPAIRED else Status.CURRENT
        } catch (e: Exception) {
            log.debug(e) { "The control store's format could not be read." }
            Status.UNKNOWN
        }
        val previous = current
        current = status
        when {
            status == Status.UNREPAIRED && previous != Status.UNREPAIRED -> log.error {
                "The control store was written before 8.0.0-beta.5: its back-references are not sets yet. Stop every " +
                    "control plane of an earlier version and run cosec-migrate repair-references; until then a change " +
                    "that touches a back-reference of the old format is refused."
            }
            status == Status.CURRENT && previous == Status.UNREPAIRED -> log.info {
                "The control store is in the format of 8.0.0-beta.5."
            }
        }
    }

    override fun close() {
        executor?.shutdownNow()
    }
}
