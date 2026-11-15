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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.StartupTask
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The eviction policy of the Redis behind the policy snapshot (RC1-47, `SNP-3`), [read] every [interval] once [start]ed,
 * on a thread of its own: what [SnapshotEvictionHealthIndicator] reports, from [status], which never reads Redis (review
 * M3 of #1240). A policy that may evict is [Status.down] whatever [mode] is, and logged at ERROR when it becomes so, not
 * on every read. One that cannot be read is down under [mode] `fail`; under `warn` the operator accepted that at startup,
 * so it is up, as `unknown`, logged once at WARN.
 *
 * A [StartupTask] (`STA-3`): the runtime half of `SNP-3`, it must read from startup on, not from the first health
 * probe, or a Redis that starts evicting is logged by no one.
 */
@InternalCoSecApi
class EvictionPolicyMonitor internal constructor(
    private val read: () -> EvictionPolicyCheck.Reading,
    private val interval: Duration,
    private val mode: RuntimeAuthorizationProperties.EvictionCheck = RuntimeAuthorizationProperties.EvictionCheck.FAIL,
) : AutoCloseable, StartupTask {
    /** Whether Redis may evict the snapshot ([down]), and what the health indicator's `eviction` detail says, if anything. */
    @InternalCoSecApi
    class Status internal constructor(val down: Boolean, val detail: String?) {
        @InternalCoSecApi
        companion object {
            val OK: Status = Status(false, null)
            val UNKNOWN: Status = Status(false, "unknown")

            /** Before the first reading: startup's check has passed, or was told to warn. */
            val PENDING: Status = Status(false, "pending")
        }
    }

    private val log = KotlinLogging.logger {}

    @Volatile
    private var current: Status = Status.PENDING
    private var executor: ScheduledExecutorService? = null

    /** What is known now of Redis's eviction of the snapshot, from the last reading. */
    fun status(): Status = current

    /** Reads every [interval] from now until [close]. */
    fun start(): EvictionPolicyMonitor = apply {
        executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "cosec-eviction-policy").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS) }
    }

    /** Reads the policy now. A read that throws is a policy that cannot be read, and the next one is still made. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun refresh() {
        val reading = try {
            read()
        } catch (e: Exception) {
            EvictionPolicyCheck.Reading.Unreadable(e.message ?: e.javaClass.simpleName)
        }
        val problem = EvictionPolicyCheck.problem(reading)
        val status = when {
            problem == null -> Status.OK
            EvictionPolicyCheck.unreadable(reading) && mode == RuntimeAuthorizationProperties.EvictionCheck.WARN ->
                Status.UNKNOWN
            else -> Status(true, problem)
        }
        val previous = current
        if (status.detail != previous.detail && !(status === Status.OK && previous === Status.PENDING)) {
            report(status)
        }
        current = status
    }

    private fun report(status: Status) {
        when {
            status.down -> log.error {
                "${status.detail}: an instance that loads an app or tenant document Redis evicted reads it as absent, " +
                    "and its DENY statements silently stop applying. Set maxmemory-policy to " +
                    "${EvictionPolicyCheck.NOEVICTION}."
            }
            status === Status.UNKNOWN -> log.warn {
                "Cannot tell whether Redis may evict CoSec's policy snapshot; reported as unknown, as " +
                    "${RuntimeAuthorizationProperties.PREFIX}.snapshot.eviction-check=warn accepts."
            }
            else -> log.info { "Redis no longer evicts CoSec's policy snapshot." }
        }
    }

    override fun close() {
        executor?.shutdownNow()
    }
}
