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
 * A cold load the compile queue refused (R2D-4): nothing is installed to keep, so it keeps its read and its waiters and
 * compiles again after `rejectedReloadRetry`, as long as more than that is left of `coldLoadTimeout` since the load
 * began; then it fails at its deadline, as a cold load that cannot finish does. A burst of distinct cold documents on a new replica queues instead of
 * failing. Not doubled, as a reload's retry is: the deadline bounds how often a cold load tries, and the documents of a
 * burst retry together, so a doubling delay would leave the pool idle between fewer and fewer waves (the executor
 * spreads them, [KeyEffects]). Each request is answered by its own timer by its deadline in any case (`SNP-17`): never
 * later, and a failure never permits.
 */
internal class ColdCompileRetries<T : Any>(private val config: KeyConfig, private val loads: KeyLoads<T>) {
    /** The queue refused cold [load]'s compilation: a retry within its deadline, or the failure once it is spent. */
    fun onRejected(step: KeyStepBuilder<T>, load: LoadState<T>, cause: SnapshotUnavailableException) {
        val left = left(step, load)
        if (left <= 0) {
            loads.onTransientFailure(step, load, cause)
            return
        }
        val retry = config.rejectedReloadRetry
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
        val cause = SnapshotUnavailableException(
            step.key,
            "the compile queue stayed full for ${config.coldLoadTimeout}"
        )
        loads.onTransientFailure(step, load, cause)
    }

    /** What is left of [load]'s cold load timeout, in nanoseconds; spent when not positive. */
    private fun left(step: KeyStepBuilder<T>, load: LoadState<T>): Long =
        config.coldLoadTimeout.toNanos() - (step.moment.nanos - load.startedNanos)
}
