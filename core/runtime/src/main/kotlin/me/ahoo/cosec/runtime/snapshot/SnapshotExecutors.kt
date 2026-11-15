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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The threads a [SnapshotSource] starts for itself: compiling, scheduling and housekeeping. */
internal object SnapshotExecutors {
    /** Queued housekeeping beyond this runs in place rather than piling up without bound. */
    const val HOUSEKEEPING_QUEUE = 4096

    fun newCompileExecutor(options: SnapshotOptions, marker: ThreadLocal<Boolean>): ThreadPoolExecutor = ThreadPoolExecutor(
        options.compileThreads,
        options.compileThreads,
        1,
        TimeUnit.MINUTES,
        ArrayBlockingQueue(options.compileQueueCapacity),
        daemonThreads("cosec-snapshot-compile", marker),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    fun newScheduler(marker: ThreadLocal<Boolean>): ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(1, daemonThreads("cosec-snapshot-scheduler", marker)).apply {
            removeOnCancelPolicy = true
        }

    fun newHousekeeping(marker: ThreadLocal<Boolean>): ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        1,
        TimeUnit.MINUTES,
        LinkedBlockingQueue(HOUSEKEEPING_QUEUE),
        daemonThreads("cosec-snapshot-housekeeping", marker),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /** Daemon threads named [prefix]-n, each marked through [marker] as the source's own. */
    fun daemonThreads(prefix: String, marker: ThreadLocal<Boolean>): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { runnable ->
            val marked = Runnable {
                marker.set(true)
                runnable.run()
            }
            Thread(marked, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
