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

package me.ahoo.cosec.runtime.snapshot

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The drains a [SnapshotSource]'s keys hand off when a thread's budget is spent (RFC 0013 B2b §3): one task in the
 * compile pool at a time, which runs them in order, so that hand-offs never compete with compilations one task per
 * key. Scheduled by a CAS, like a drain: a hand-off made while the task runs is taken by it, or by the task scheduled
 * after it. A pool that refuses the task sends it to housekeeping, and when that refuses too, it runs in place.
 */
internal class DrainHandOffs(private val threads: SnapshotThreads) {
    private val waiting = ConcurrentLinkedQueue<Runnable>()
    private val scheduled = AtomicBoolean()

    fun handOff(drain: Runnable) {
        waiting.add(drain)
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            threads.compileExecutor.execute(::run)
        } catch (_: RejectedExecutionException) {
            if (!threads.offTimer(::run)) run()
        }
    }

    private fun run() {
        try {
            while (true) {
                (waiting.poll() ?: break).run()
            }
        } finally {
            scheduled.set(false)
            // A hand-off made between the last poll and the release is taken by the task this schedules.
            if (waiting.isNotEmpty()) schedule()
        }
    }
}
