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
 * lost, and the periodic passes alone must install each write in time: "a change whose invalidation was lost is
 * installed by the first pass that reaches its document after the change is readable". Reads answer and compilations
 * run as soon as they are asked for; time moves in [STEP]s, so the passes fall due as they would. A pass over the n
 * written documents takes at most n steps here: a lane's next read starts once its previous load ends, and this pilot
 * answers a read at the step after it starts.
 *
 * What goes wrong meanwhile is the script's [Lag], and the bound follows from it ([boundFor]):
 * - `NONE`: the next pass: within one interval of the write, plus the pass itself.
 * - `HANG_READ`: the target's first read never answers. The pass that holds it is never abandoned (it has a read in
 *   flight) until the read times out, at most `storeReadTimeout` after the write; the pass then ends, the pass it owes
 *   starts at once, or at the next tick, and installs every write. With one lane the hang holds the whole pass; with
 *   two, the other lane may install the others first, which the bound allows.
 * - `STUCK_COMPILE`: the target's compilation never runs, and the others are written once it is stuck. The pass waiting
 *   on it makes no progress for `storeReadTimeout`; the first tick after that abandons it and starts the next, which
 *   does not wait for a load in flight longer than `storeReadTimeout`. So: the timeout, the next tick, the pass. With one
 *   lane only that next pass can install the others; with two, the stuck pass's other lane may.
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
    private val interval: Duration = options.revalidateInterval
    private val timeout: Duration = options.storeReadTimeout
    private lateinit var bound: Duration
    private var target: SnapshotKey? = null
    private var hung: SlotModelStore.Read? = null
    private var stuck: Runnable? = null

    /** How long after the write the passes have to install [n] documents' changes, for [lag] (see the class comment). */
    private fun boundFor(lag: Lag, n: Int): Duration {
        val pass = STEP.multipliedBy(n.toLong() + 1)
        return when (lag) {
            Lag.NONE -> interval.plus(pass)
            Lag.HANG_READ, Lag.STUCK_COMPILE -> timeout.plus(interval).plus(pass)
        }
    }

    fun run() {
        val written = model.installedKeys()
        val target = script.lagTarget.takeIf { script.lag != Lag.NONE && it in written }
        this.target = target
        if (target != null) {
            // The first pass after the write reaches the target, as with nothing wrong.
            bound = boundFor(Lag.NONE, written.size)
            write(target)
            pilot { hung != null || stuck != null }
            if (hung == null && stuck == null) {
                model.violations += "no pass reached $target within $bound of its write, whose notification was lost"
                return
            }
        }
        bound = boundFor(if (target == null) Lag.NONE else script.lag, written.size)
        val checked = written.filterNot { it == target && script.lag == Lag.STUCK_COMPILE }
        checked.filterNot { it == target }.forEach(::write)
        pilot { checked.all(model::servesCurrent) }
        for (key in checked) {
            if (!model.isInstalled(key)) continue
            model.lostWriteChecked(if (target == null) Lag.NONE else script.lag)
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
