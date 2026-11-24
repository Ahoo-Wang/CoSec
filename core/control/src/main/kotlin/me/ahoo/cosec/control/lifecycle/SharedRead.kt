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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * A read of the store that every caller on this control host shares (`CTL-62`, `CTL-68`): made at most once per
 * [maxAge], one read for the calls that wait for it, its value kept with when it was read. A read that fails is never
 * kept and never answered from an older one: the calls waiting on it fail as [load] failed, and the next call reads
 * again (`CTL-52`).
 *
 * One load at a time, and every call that finds no fresh read while it runs waits for that load, its failure included
 * (DR1-F-10): a call waits at most one load, never the loads of the calls queued ahead of it, so a store that answers
 * slowly with a failure costs each caller one of its bounded reads (`CTL-82`), not one per caller before it. A call
 * waiting on a load whose own caller was cancelled loads again itself: it was not cancelled.
 * For reads whose answer is the whole of a large stored value (every instance's status, every document's key), where a
 * few seconds of age cost less than reading it per call.
 */
internal class SharedRead<T>(
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
    private val load: suspend () -> T,
) {
    /** What one read answered, and when it was read. */
    class Read<T>(val value: T, val asOf: Instant)

    private val lock = Any()

    @Volatile
    private var last: Read<T>? = null

    /** The load in flight, which every call that finds no fresh read waits on; null between loads (under [lock]). */
    private var inflight: CompletableDeferred<Read<T>?>? = null

    suspend fun read(): Read<T> {
        while (true) {
            fresh()?.let { return it }
            var mine = false
            val shared = synchronized(lock) {
                fresh()?.let { return it }
                inflight ?: CompletableDeferred<Read<T>?>().also {
                    inflight = it
                    mine = true
                }
            }
            if (mine) return loadInto(shared)
            // Null: the call that loaded was cancelled, and this one was not: it loads, or waits on the next load.
            shared.await()?.let { return it }
        }
    }

    /** Loads, and gives what the load ends in to every call waiting on [shared]: the read, or the failure. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadInto(shared: CompletableDeferred<Read<T>?>): Read<T> {
        try {
            val read = clock.instant().let { asOf -> Read(load(), asOf) }
            last = read
            shared.complete(read)
            return read
        } catch (e: Throwable) {
            // A cancellation of this call is its own; one while it is still active is the store's (a timeout), and
            // the waiters get it as this call does.
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                shared.complete(null)
            } else {
                shared.completeExceptionally(e)
            }
            throw e
        } finally {
            synchronized(lock) { if (inflight === shared) inflight = null }
        }
    }

    private fun fresh(): Read<T>? = last?.takeIf { Duration.between(it.asOf, clock.instant()) < maxAge }

    companion object {
        /**
         * How old a shared read may be: 5 seconds. Well under the minute after which an instance's status is ignored,
         * and short against how often a person or a service acts on what a listing shows; long against how often a
         * caller pages or polls, which is what it saves (a full paging of 50,000 documents at the default limit is
         * thousands of pages, one read).
         */
        val MAX_AGE: Duration = Duration.ofSeconds(5)
    }
}
