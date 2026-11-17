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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a caller of a [KeyRunner] waits on (RFC 0013 B2b §2): the future it holds, which the transition's effects
 * answer. One per call; it hangs on its load's [KeyHandle] as a dependent, outside the state.
 */
internal sealed class KeyWaiter<T : Any, R> : CompletableFuture<R>(), KeyEvent.Waiter {
    /** Waits for [handle]: its answer is this waiter's. */
    abstract fun hang(handle: KeyHandle<T>)

    /** Answered at once: the installed entry ([hit] for a request's), or a failure after close. */
    abstract fun serve(entry: SnapshotEntry<T>, hit: Boolean)

    /** A pass or a revalidation does not wait for this key. */
    abstract fun skip()

    /** A request after close, or what the event carried when its transition threw (§5). */
    open fun fail(error: Throwable) {
        completeExceptionally(error)
    }

    /** `usable`, `reload`: an entry, never skipped. */
    class Entry<T : Any> : KeyWaiter<T, SnapshotEntry<T>>() {
        override fun hang(handle: KeyHandle<T>) {
            handle.future.whenComplete { entry, error -> if (error != null) completeExceptionally(error) else complete(entry) }
        }

        override fun serve(entry: SnapshotEntry<T>, hit: Boolean) {
            complete(entry)
        }

        override fun skip() {
            completeExceptionally(IllegalStateException("A request for an entry was skipped."))
        }
    }

    /** `reloadIfHeld`, `load(pass)`: the entry, or skipped. */
    class Held<T : Any> : KeyWaiter<T, Reloaded<T>>() {
        override fun hang(handle: KeyHandle<T>) {
            handle.future.whenComplete { entry, error ->
                if (error != null) completeExceptionally(error) else complete(Reloaded.Loaded(entry))
            }
        }

        override fun serve(entry: SnapshotEntry<T>, hit: Boolean) {
            complete(Reloaded.Loaded(entry))
        }

        override fun skip() {
            complete(Reloaded.Skipped)
        }
    }

    /**
     * One request's view (`forRequest`): created, registered in `pendingRequests` and timed by the caller before it
     * offers ([arm]), so no request's bound depends on the drain. Whoever answers first (its load's handle, its
     * timeout, or close) claims it; an answer records the outcome before completing, so a caller that sees the result
     * also sees the metric.
     */
    class Request<T : Any>(private val key: SnapshotKey, private val context: SlotContext) :
        KeyWaiter<T, SnapshotEntry<T>>(), PendingRequest {
        private val answered = AtomicBoolean()

        @Volatile
        private var timeout: ScheduledFuture<*>? = null

        /** Registered, then timed by the cold load timeout; after close, aborted at once. */
        fun arm(): Request<T> {
            context.pendingRequests.add(this)
            if (context.closed.get()) {
                abort(context.closedError())
                return this
            }
            val bound = context.options.coldLoadTimeout
            timeout = try {
                context.threads.scheduler.schedule(
                    {
                        val error = SnapshotUnavailableException(key, "not loaded within $bound", TimeoutException())
                        answer(failedEntry(key, error, context.clock.instant()), LoadOutcome.TIMEOUT)
                    },
                    bound.toNanos(),
                    TimeUnit.NANOSECONDS,
                )
            } catch (rejected: RejectedExecutionException) {
                answer(transientFailure(key, context.clock.instant(), rejected), LoadOutcome.FAILED)
                null
            }
            return this
        }

        override fun hang(handle: KeyHandle<T>) {
            handle.future.whenComplete { entry, error ->
                val result = entry ?: transientFailure(key, context.clock.instant(), unwrapped(error))
                answer(result, if (result.state is DocumentState.Failed) LoadOutcome.FAILED else LoadOutcome.COLD)
            }
        }

        override fun serve(entry: SnapshotEntry<T>, hit: Boolean) {
            answer(entry, if (hit) LoadOutcome.HIT else LoadOutcome.COLD)
        }

        override fun skip() {
            abort(IllegalStateException("A request was skipped."))
        }

        override fun fail(error: Throwable) = abort(error)

        override fun abort(error: Throwable) {
            if (answered.compareAndSet(false, true)) {
                context.pendingRequests.remove(this)
                timeout?.cancel(false)
                completeExceptionally(error)
            }
        }

        private fun answer(entry: SnapshotEntry<T>, outcome: LoadOutcome) {
            if (!answered.compareAndSet(false, true)) return
            context.pendingRequests.remove(this)
            timeout?.cancel(false)
            try {
                context.metrics.onLoad(key.kind, outcome)
            } finally {
                complete(entry)
            }
        }

        private fun unwrapped(error: Throwable?): Throwable =
            (error as? CompletionException)?.cause ?: error ?: IllegalStateException("The load completed without a result.")
    }
}
