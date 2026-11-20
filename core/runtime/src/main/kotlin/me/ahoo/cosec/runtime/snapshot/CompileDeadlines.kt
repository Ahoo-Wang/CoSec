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
import me.ahoo.cosec.runtime.snapshot.KeyEffect.Level
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The deadline of a load's compilation (R2D-11). A load's read is timed (`storeReadTimeout`) and each request that waits
 * on it has its own deadline, but nothing timed the compilation: one that never returned kept its load in flight for
 * good, and its key never refreshed. Now each compilation handed to the pool carries the load's deadline, its budget
 * since it began (`coldLoadTimeout` for a cold load, `storeReadTimeout` for a reload), and the periodic tick sweeps those
 * past it ([sweep]): a load whose compilation has not answered by then ends as a failure, within one
 * `revalidateInterval` of the deadline. A cold load answers failed, a reload keeps what is installed (F6), and a
 * recompile fails closed (`RECOMPILE_PENDING`), as for any failure to compile again. No timer per load: the sweep reads a
 * map that holds only the compilations in flight.
 *
 * A compilation still queued is abandoned and never starts. One that runs is not interrupted: the compiler makes no
 * blocking call that an interrupt would end, so it runs on. Its revision is marked stuck while it runs
 * ([KeyContext.stuckCompiles]): no load starts another compilation of it, and such a load times out at once. So at most
 * one compile thread is held per stored revision, a revision that does not compile in time costs one thread, and a new
 * revision of the document compiles as any other. Only as many distinct such revisions as there are compile threads
 * would hold the whole pool; then no other compilation runs, every load fails closed at its deadline, and each
 * stuck revision's line names it.
 */
internal class CompileDeadlines<T : Any>(private val config: KeyConfig, private val loads: KeyLoads<T>) {
    /** [load]'s compilation of [read] did not answer by its deadline: the load fails, and the compilation is abandoned. */
    fun onTimedOut(step: KeyStepBuilder<T>, load: LoadState<T>, read: SnapshotRead.Present) {
        val budget = budget(config, load)
        step.emit(KeyEffect.AbandonCompile(load.id))
        step.emit(
            KeyEffect.Log(
                Level.ERROR,
                "Compiling snapshot document [${step.key}] revision ${read.document.revision} did not end within " +
                    "$budget; the load fails, and the revision is not compiled again while that compilation runs.",
            )
        )
        val cause = SnapshotUnavailableException(
            step.key,
            "its compilation of revision ${read.document.revision} did not end within $budget",
        )
        loads.onTransientFailure(step, load, cause)
    }

    /**
     * A compilation handed to the pool: [loadId]'s, of [digest], due by [deadline], answering to [owner]. Starts once,
     * unless abandoned first; abandoned once, by the sweep's timeout.
     */
    internal class Running(
        val loadId: Long,
        val digest: SnapshotDigest,
        val deadline: Long,
        private val owner: KeyOwner<*>,
    ) {
        private val state = AtomicInteger(QUEUED)

        /** Whether its timeout was offered; set by the sweep only. */
        @Volatile
        var offered: Boolean = false
            private set

        /** On the pool: whether it may run, not having been abandoned. */
        fun start(): Boolean = state.compareAndSet(QUEUED, RUNNING)

        /** On the drain: whether it runs, so that its revision is stuck; a queued one never starts. */
        fun abandon(): Boolean = state.getAndSet(ABANDONED) == RUNNING

        /** Offers its load's timeout, once. */
        fun timeOut() {
            offered = true
            owner.offer(KeyEvent.CompileTimedOut(loadId))
        }

        private companion object {
            const val QUEUED = 0
            const val RUNNING = 1
            const val ABANDONED = 2
        }
    }

    companion object {
        /** When [load]'s budget is spent, in the source's monotonic nanoseconds. */
        fun <T : Any> of(
            config: KeyConfig,
            load: LoadState<T>
        ): Long = load.startedNanos + budget(config, load).toNanos()

        /** On the periodic tick: the loads whose compilation is past its deadline time out (R2D-11). */
        fun sweep(context: KeyContext, now: Long) {
            if (context.compiling.isEmpty()) return
            for (running in context.compiling.values) {
                if (!running.offered && now - running.deadline > 0) running.timeOut()
            }
        }

        /**
         * Load [loadId]'s compilation of [key] timed out: abandoned, so that it does not start if it still waits in the
         * queue, and its revision stuck if it runs. Marked, then checked against what runs, as the compilation clears
         * both when it returns: a mark never outlives it.
         */
        fun abandon(context: KeyContext, key: SnapshotKey, loadId: Long) {
            val running = context.compiling[key]?.takeIf { it.loadId == loadId } ?: return
            if (!running.abandon()) return
            context.stuckCompiles[key] = running.digest
            if (context.compiling[key] !== running) context.stuckCompiles.remove(key, running.digest)
        }

        /** Whether a compilation of [key]'s revision [digest] timed out and still runs. */
        fun stuck(context: KeyContext, key: SnapshotKey, digest: SnapshotDigest): Boolean =
            context.stuckCompiles.isNotEmpty() && context.stuckCompiles[key] == digest

        private fun <T : Any> budget(config: KeyConfig, load: LoadState<T>): Duration =
            if (load.known == null) config.coldLoadTimeout else config.storeReadTimeout
    }
}
