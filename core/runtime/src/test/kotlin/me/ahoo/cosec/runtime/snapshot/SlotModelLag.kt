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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SlotModelScript.Lag
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * The lag phase of a run (`SNP-6`): with the store healthy, every installed document is written with its notification
 * lost, and the periodic passes alone must install each write within [bound]: two intervals and two store read
 * timeouts of virtual time. Reads answer and compilations run as soon as they are asked for; time moves in [STEP]s,
 * so the passes fall due as they would.
 *
 * What goes wrong meanwhile is the script's [Lag]: the target's first read hangs (its pass has a read in flight, and
 * the read times out), or its compilation never runs (its pass waits on it until abandoned). For a stuck target the
 * target is written first, and the others only once it is stuck: they then need a pass after the stuck one.
 */
internal class SlotModelLag(
    private val script: SlotModelScript,
    private val store: SlotModelStore,
    private val threads: SlotModelThreads,
    private val model: SlotModel,
    options: SnapshotOptions,
    /** The model's checks now; false once one failed. */
    private val check: () -> Boolean,
) {
    private val bound: Duration = options.revalidateInterval.plus(options.storeReadTimeout).multipliedBy(2)
    private var target: SnapshotKey? = null
    private var hung: SlotModelStore.Read? = null
    private var stuck: Runnable? = null

    fun run() {
        val written = model.installedKeys()
        val target = script.lagTarget.takeIf { script.lag != Lag.NONE && it in written }
        this.target = target
        if (target != null) {
            write(target)
            pilot { hung != null || stuck != null }
        }
        val checked = written.filterNot { it == target && script.lag == Lag.STUCK_COMPILE }
        checked.filterNot { it == target }.forEach(::write)
        pilot { checked.all(model::servesCurrent) }
        for (key in checked) {
            if (!model.isInstalled(key)) continue
            model.lostWriteChecked()
            if (!model.servesCurrent(key)) {
                model.violations += "a write of $key whose notification was lost was not installed within $bound (${script.lag})"
            }
        }
    }

    private fun write(key: SnapshotKey) {
        store.dropNotifications = true
        store.put(key)
        store.dropNotifications = false
    }

    /** Answers reads and runs what is queued, a [STEP] at a time, until [done] or [bound] has passed. */
    private fun pilot(done: () -> Boolean) {
        val until = threads.nanos + bound.toNanos()
        while (threads.nanos < until && !done()) {
            answer()
            while (threads.compiles.any { it !== stuck }) threads.runCompile(
                threads.compiles.indexOfFirst { it !== stuck }
            )
            while (threads.housekeeping.tasks.isNotEmpty()) threads.runHousekeeping(0)
            if (!check() || done()) return
            threads.advance(STEP)
            if (!check()) return
        }
    }

    private fun answer() {
        for (read in store.pending.toList()) {
            if (read.hung) continue
            if (firstOfTarget(read, Lag.HANG_READ) && hung == null) {
                read.hung = true
                hung = read
                continue
            }
            val queued = threads.compiles.size
            read.release(atStart = false)
            if (firstOfTarget(read, Lag.STUCK_COMPILE) && stuck == null && threads.compiles.size > queued) {
                stuck = threads.compiles.last()
            }
        }
    }

    /** Whether [read] is of the target, in a run whose lag is [lag]. */
    private fun firstOfTarget(read: SlotModelStore.Read, lag: Lag): Boolean = read.key == target && script.lag == lag

    private companion object {
        val STEP: Duration = Duration.ofMillis(500)
    }
}
