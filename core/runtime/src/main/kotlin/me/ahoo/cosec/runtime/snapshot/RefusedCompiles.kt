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
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.time.Duration

/**
 * A compilation the compile queue refused, of a load that has nothing it may keep serving as the outcome of this load: a
 * cold load (R2D-4), with nothing installed, and a recompile (R2D-12), whose installed entry was compiled against
 * inputs that have changed since. Either keeps its read and its waiters and compiles again after `coldCompileRetry`, as
 * long as more than that is left of `coldLoadTimeout` since the load began. Then it fails at its deadline as the load
 * would fail otherwise: a cold load without installing, a recompile closed (`RECOMPILE_PENDING`), as one that does not
 * compile.
 *
 * A refusal says nothing about the document, so a recompile does not fail closed for it at once: while it waits,
 * requests are decided on the installed entry, compiled against the previous platform document, as they are while its
 * read runs, for at most `coldLoadTimeout` since its load began. That entry was what the source served until the
 * platform's inputs changed, and still holds what the stored document grants; what it may lack is a limit or an
 * attribute declaration the new platform document refuses it for, which the compile after the wait applies or turns into
 * the closed failure. The load's waiters (a `recompile` call's future among them, `SNP-13`) wait for its end, never for
 * that entry. Well within `storeReadTimeout`, so a revalidation pass never takes a waiting recompile for a stuck one.
 *
 * Not the reload's retry, which starts at 200 ms and doubles: a document compiles in a fraction of a millisecond, so the
 * pool works through its queue in milliseconds, and the documents of a burst or of a platform change retry together,
 * so a long or doubling delay would leave the pool idle between fewer and fewer waves (the executor spreads them,
 * [KeyEffects]). Each request is answered by its own timer by its deadline in any case (`SNP-17`): never later, and a
 * failure never permits.
 */
internal class RefusedCompiles<T : Any>(private val config: KeyConfig, private val loads: KeyLoads<T>) {
    /** The queue refused [load]'s compilation: a retry within its deadline, or the failure once it is spent. */
    fun onRejected(step: KeyStepBuilder<T>, load: LoadState<T>, cause: SnapshotUnavailableException) {
        val left = left(step, load)
        if (left <= 0) {
            loads.onTransientFailure(step, load, cause)
            return
        }
        val retry = config.coldCompileRetry
        // Within the last retry's window, the load wakes once more, at its deadline, and fails: a retry that close to it
        // would only end later, and tries never shrink toward nothing.
        val effect = if (left > retry.toNanos()) {
            KeyEffect.ScheduleCompileRetry(load.id, retry, spread = true)
        } else {
            KeyEffect.ScheduleCompileRetry(load.id, Duration.ofNanos(left), spread = false)
        }
        step.emit(effect)
    }

    /** [load]'s retry came due: [read] compiles again, unless its deadline came. */
    fun onRetryDue(step: KeyStepBuilder<T>, load: LoadState<T>, read: SnapshotRead.Present) {
        if (left(step, load) > 0) {
            step.emit(KeyEffect.Compile(load.id, read))
            return
        }
        val cause = SnapshotUnavailableException(step.key, "the compile queue stayed full for ${config.coldLoadTimeout}")
        loads.onTransientFailure(step, load, cause)
    }

    /** What is left of [load]'s `coldLoadTimeout`, in nanoseconds; spent when not positive. */
    private fun left(step: KeyStepBuilder<T>, load: LoadState<T>): Long =
        config.coldLoadTimeout.toNanos() - (step.moment.nanos - load.startedNanos)

    internal companion object {
        /** Whether a refused compilation of [load] waits for the queue: one that has nothing it may keep serving. */
        fun <T : Any> waits(load: LoadState<T>): Boolean = load.known == null || load.recompiles
    }
}
