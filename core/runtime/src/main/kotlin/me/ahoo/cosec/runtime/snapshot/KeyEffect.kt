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
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Waiter
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * What a transition asks the runner to do (RFC 0013 B2b), executed in list order. A transition that installs emits
 * [Install] before any [Answer], so no waiter is answered before the entry it gets is published. Each effect names the
 * [Slot] code it replaces, and the page says on which thread it runs: only the compile pool executes [Compile].
 */
internal sealed interface KeyEffect<out T : Any> {
    /** `pass?.startRead()` as a load starts: counts the load's read as one of [ticket]'s pass. */
    data class StartPassRead(val ticket: PassTicket) : KeyEffect<Nothing>

    /** `passRead.end()`: the read is over, whatever ended it. */
    data class EndPassRead(val ticket: PassTicket) : KeyEffect<Nothing>

    /**
     * `read`, `readFor`, `withTimeout`: starts load [loadId]'s read, from a local copy when [fromLocalCopy], otherwise
     * conditionally on [digest], and arms its [KeyEvent.ReadTimedOut] unless the read has answered already.
     */
    data class StartRead(val loadId: Long, val fromLocalCopy: Boolean, val digest: SnapshotDigest?) : KeyEffect<Nothing>

    /** `timeout.cancel`: load [loadId]'s read answered, so its timeout has nothing left to do. */
    data class CancelReadTimeout(val loadId: Long) : KeyEffect<Nothing>

    /** `compileOffThread`: compiles what load [loadId] read, on the compile pool only. */
    data class Compile(val loadId: Long, val read: SnapshotRead.Present) : KeyEffect<Nothing>

    /** A write of `families.seen`, which only this key's runner writes; [ifAbsent] keeps one already there. */
    data class RecordSeen(val seen: Seen, val ifAbsent: Boolean) : KeyEffect<Nothing>

    /** `known.confirm(at)`: what the store answered still holds as of [at]. */
    data class Confirm(val entry: SnapshotEntry<*>, val at: Instant) : KeyEffect<Nothing>

    /**
     * `recordInstall` and the write of `installed`: the metrics (a regression from [regressedFrom] included), the
     * family's counts moved from [previous], then the volatile write, which nothing else does.
     */
    data class Install<out T : Any>(
        val entry: SnapshotEntry<T>,
        val previous: SnapshotEntry<T>?,
        val regressedFrom: Seen?
    ) : KeyEffect<T>

    /** A metric that is not an install's. */
    data class Report(val metric: Metric) : KeyEffect<Nothing>

    /** A log line, written on housekeeping, never on a request's thread; dropped and counted as [task] if refused. */
    data class Log(
        val level: Level,
        val message: String,
        val cause: Throwable? = null,
        val task: HousekeepingTask = HousekeepingTask.LOAD_LOG,
    ) : KeyEffect<Nothing>

    /** Completes [handle], and with it every waiter hanging on it. */
    data class Answer<T : Any>(val handle: KeyHandle<T>, val entry: SnapshotEntry<T>) : KeyEffect<T>

    /** [waiter] waits for [handle]: the runner attaches it, outside the state. */
    data class Hang<T : Any>(val waiter: Waiter, val handle: KeyHandle<T>) : KeyEffect<T>

    /** [waiter] gets [entry] at once: what is installed ([hit] for a request), or a failure after close. */
    data class Serve<out T : Any>(
        val waiter: Waiter,
        val entry: SnapshotEntry<T>,
        val hit: Boolean = false
    ) : KeyEffect<T>

    /** A request after close fails with [error], as today's view is aborted. */
    data class Refuse(val waiter: Waiter, val error: Throwable) : KeyEffect<Nothing>

    /** A pass's or a revalidation's waiter is not joined: the key was removed, or its load is stuck (`SNP-6`). */
    data class Skip(val waiter: Waiter) : KeyEffect<Nothing>

    /** The key was removed: [event] goes, with its waiter, to the key's current runner. */
    data class Redispatch(val event: KeyEvent.Requested) : KeyEffect<Nothing>

    /** `retryLater`: a [KeyEvent.RetryDue] after [delay], on the scheduler. */
    data class ScheduleRetry(val delay: Duration) : KeyEffect<Nothing>

    /** `housekeep` of an empty key: drop it, on housekeeping, if it is still empty then. */
    data object DropIfEmpty : KeyEffect<Nothing>

    /** Before a load's answers: an install that took the family over its cap evicts in place (`SNP-14`). */
    data object EvictOverCap : KeyEffect<Nothing>

    /** `housekeep` after a load's answers: evict the family beyond its bound, on housekeeping (in place over the cap). */
    data object EvictBeyond : KeyEffect<Nothing>

    /** `family.drop`: the key leaves the family's map; unless the removal was [claimed], it moves the count itself. */
    data class Drop(val claimed: Boolean) : KeyEffect<Nothing>

    /** The answer of `markRecompile` or `removeIfIdle`. */
    data class Reply(val reply: CompletableFuture<Boolean>, val value: Boolean) : KeyEffect<Nothing>

    /** The metrics a transition reports beside installs. */
    sealed interface Metric {
        data class DocumentLost(val revision: Long) : Metric
    }

    enum class Level { DEBUG, WARN, ERROR }
}
