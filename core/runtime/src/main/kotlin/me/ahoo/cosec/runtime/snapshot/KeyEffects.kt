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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.KeyEffect.Level
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/**
 * Executes what [KeyTransitions] asks of one key (RFC 0013 B2b §2), inside its runner's drain, in list order. It
 * reaches its runner only through [owner]: an event (a read's answer, a compilation, a timer) is offered behind what is
 * queued, an entry published, the key dropped from its family, a request redispatched to the key's current runner, the
 * key's own eviction offered if it holds nothing.
 */
internal class KeyEffects<T : Any>(
    private val key: SnapshotKey,
    private val compiler: SnapshotCompiler<T>,
    private val context: KeyContext,
    private val owner: KeyOwner<T>,
) {
    /**
     * Made at a key's first failure to compile and kept from then on (RFC 0013 B2b step 3): most keys never fail. Made
     * under this object's lock, since the compile pool reaches it outside the drain.
     */
    @Volatile
    private var report: CompileReport? = null

    /** The installed entry no review has run for yet (UC-1): set by [review], cleared by the review's task. */
    @Volatile
    private var unreviewed: SnapshotEntry<T>? = null

    /** The store-read timeout of each load whose read is out, made at the first read; touched inside the drain only. */
    private var readTimeouts: HashMap<Long, ScheduledFuture<*>>? = null

    private fun timeouts(): HashMap<Long, ScheduledFuture<*>> = readTimeouts ?: HashMap<Long, ScheduledFuture<*>>().also {
        readTimeouts = it
    }

    private fun report(): CompileReport = report ?: synchronized(this) {
        report ?: CompileReport(key, compiler) { line, task ->
            context.threads.offHousekeeping(HousekeepingTask.COMPILE_LOG, line, task)
        }.also { report = it }
    }

    @Suppress("CyclomaticComplexMethod", "UNCHECKED_CAST")
    fun execute(effect: KeyEffect<T>) {
        when (effect) {
            is KeyEffect.StartPassRead -> {
                effect.ticket.read = effect.ticket.pass.startRead()
                // A review housekeeping refused is offered again when a pass visits the key (UC-1).
                unreviewed?.let(::review)
            }
            is KeyEffect.EndPassRead -> effect.ticket.read?.end()
            is KeyEffect.StartRead -> startRead(effect)
            is KeyEffect.CancelReadTimeout -> readTimeouts?.remove(effect.loadId)?.cancel(false)
            is KeyEffect.Compile -> compile(effect.loadId, effect.read)
            is KeyEffect.RecordSeen -> recordSeen(effect)
            is KeyEffect.Confirm -> effect.entry.confirm(effect.at)
            is KeyEffect.Install -> installed(effect)
            is KeyEffect.Report -> report(effect.metric)
            is KeyEffect.Log ->
                context.threads.offHousekeeping(
                    effect.task,
                    { HousekeepingRefusals.line(effect.message, effect.cause) }
                ) {
                    write(effect)
                }
            is KeyEffect.Answer -> effect.handle.future.complete(effect.entry)
            is KeyEffect.Hang -> (effect.waiter as KeyWaiter<T, *>).hang(effect.handle)
            is KeyEffect.Serve -> (effect.waiter as KeyWaiter<T, *>).serve(effect.entry, effect.hit)
            is KeyEffect.Refuse -> (effect.waiter as KeyWaiter<T, *>).fail(effect.error)
            is KeyEffect.Skip -> (effect.waiter as KeyWaiter<T, *>).skip()
            is KeyEffect.Redispatch -> owner.redispatched(effect.event)
            is KeyEffect.ScheduleRetry -> scheduleRetry(effect.delay)
            KeyEffect.DropIfEmpty ->
                context.threads.offHousekeeping(HousekeepingTask.DROP, key) { owner.dropIfEmpty() }
            KeyEffect.EvictOverCap -> evictOverCap()
            KeyEffect.EvictBeyond -> evictBeyond()
            is KeyEffect.Drop -> owner.dropped(effect.claimed)
            is KeyEffect.Reply -> effect.reply.complete(effect.value)
        }
    }

    /** §5: a load ended by a transition that threw has no read left to time. */
    fun cancelReadTimeout(loadId: Long) {
        readTimeouts?.remove(loadId)?.cancel(false)
    }

    /** The store call only starts the read; its answer is offered from the store client's thread, behind what is queued. */
    private fun startRead(effect: KeyEffect.StartRead) {
        val id = effect.loadId
        val reading = if (effect.fromLocalCopy) {
            context.store.read(
                key
            )
        } else {
            context.store.readIfChanged(key, effect.digest)
        }
        if (!reading.isDone) {
            val bound = context.options.storeReadTimeout
            try {
                val timeout = context.threads.scheduler.schedule(
                    { owner.offer(KeyEvent.ReadTimedOut(id)) },
                    bound.toNanos(),
                    TimeUnit.NANOSECONDS,
                )
                timeouts()[id] = timeout
            } catch (rejected: RejectedExecutionException) {
                log.debug { "No store read timeout: $rejected" }
            }
        }
        reading.whenComplete { read: SnapshotRead?, error: Throwable? ->
            owner.offer(
                when {
                    error != null -> KeyEvent.ReadFailed(id, error)
                    read == null -> KeyEvent.ReadFailed(id, IllegalStateException("The store answered null."))
                    else -> KeyEvent.ReadDone(id, read)
                }
            )
        }
    }

    /** On the compile pool only; its answer, a crash or a refusal is offered back. */
    private fun compile(id: Long, read: SnapshotRead.Present) {
        try {
            context.threads.compileExecutor.execute { compileOnPool(id, read) }
        } catch (rejected: RejectedExecutionException) {
            owner.offer(KeyEvent.CompileRejected(id, rejected))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun compileOnPool(id: Long, read: SnapshotRead.Present) {
        val revision = read.document.revision
        val begin = System.nanoTime()
        val state: DocumentState<T> = try {
            DocumentState.Present(compiler.compile(key, read.document))
        } catch (error: VirtualMachineError) {
            // RC1-43: installed as a document that does not compile, then rethrown to the pool.
            owner.offer(
                KeyEvent.CompileCrashed(
                    id,
                    SnapshotUnavailableException(key, report().failedToCompile(revision), error)
                )
            )
            throw error
        } catch (error: Throwable) {
            DocumentState.Failed(SnapshotUnavailableException(key, report().failedToCompile(revision), error))
        }
        // Reported on the pool, for a compilation that ran (it compiled, or it threw),
        // before its result is installed or `closed` is read, so a close that ends the load does not hide the work. A
        // meter's `VirtualMachineError` (the guard lets nothing else out) leaves after the result is offered.
        try {
            context.metrics.onCompile(
                key.kind,
                Duration.ofNanos(System.nanoTime() - begin),
                state is DocumentState.Present
            )
        } finally {
            owner.offer(KeyEvent.Compiled(id, state))
        }
        // Between compilations the pool serves the hand-offs: a caller's event left by a spent budget waits at most for
        // the compilation a pool thread is running, not for the compilations queued behind it.
        context.handOffs.runPending()
    }

    private fun recordSeen(effect: KeyEffect.RecordSeen) {
        if (effect.ifAbsent) {
            context.families.seen.putIfAbsent(
                key,
                effect.seen
            )
        } else {
            context.families.seen[key] = effect.seen
        }
    }

    /** `recordInstall`: the metrics first, then, whatever they did, the entry is published. */
    private fun installed(effect: KeyEffect.Install<T>) {
        try {
            try {
                context.metrics.onInstall(effect.entry)
            } finally {
                effect.regressedFrom?.let { context.metrics.onRegression(key, it.revision, effect.entry.revision) }
            }
            // Without a report, no failure was logged: only a failed compilation needs one made.
            val made = report ?: if (effect.entry.origin == EntryOrigin.UNCOMPILABLE) report() else null
            made?.installed(effect.entry)
            if (effect.entry.origin == EntryOrigin.DOCUMENT && context.reviews.isNotEmpty()) {
                review(effect.entry)
            }
        } finally {
            owner.published(effect.previous, effect.entry)
        }
    }

    /**
     * UC-1: [entry] to the [InstallReview]s, off housekeeping. Until housekeeping runs it, it is this key's [unreviewed]
     * entry: a refused offer leaves it there for the next pass, and a later install replaces it, since only the
     * installed revision is worth a review.
     */
    private fun review(entry: SnapshotEntry<T>) {
        val compiled = (entry.state as? DocumentState.Present)?.compiled ?: return
        unreviewed = entry
        context.threads.offHousekeeping(HousekeepingTask.INSTALL_REVIEW, key) {
            if (unreviewed === entry) {
                unreviewed = null
            }
            for (review in context.reviews) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    review.installed(key, entry.revision, compiled)
                } catch (error: Exception) {
                    log.warn(
                        error
                    ) { "The review of [$key] revision ${entry.revision} failed; the document stays installed." }
                }
            }
        }
    }

    private fun report(metric: KeyEffect.Metric) {
        when (metric) {
            is KeyEffect.Metric.DocumentLost -> context.metrics.onDocumentLost(key, metric.revision)
        }
    }

    private fun write(effect: KeyEffect.Log) {
        when (effect.level) {
            Level.DEBUG -> log.debug(effect.cause) { effect.message }
            Level.WARN -> log.warn(effect.cause) { effect.message }
            Level.ERROR -> log.error(effect.cause) { effect.message }
        }
    }

    private fun scheduleRetry(delay: Duration) {
        try {
            context.threads.scheduler.schedule(
                { owner.offer(KeyEvent.RetryDue()) },
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            )
        } catch (rejected: RejectedExecutionException) {
            log.debug { "No reload retry: $rejected" }
        }
    }

    /**
     * Before a load's answers: when the install took the family over its cap, the pass runs here, on the installing
     * thread whatever it is, with the drain budget lifted: `SNP-14`'s back-pressure is the installer's, so the bound
     * never depends on a pool being free. The victims whose flags are free are evicted on this thread; a
     * victim another thread is draining takes its eviction next, in that drain.
     */
    private fun evictOverCap() {
        val families = context.families
        val family = families.firstOrNull { it.spec.kind == key.kind } ?: return
        if (families.overCap(family)) DrainBudget.unbounded { families.evictBeyond(family) }
    }

    /**
     * After a load's answers: nothing when both kinds are within their bounds; otherwise housekeeping evicts beyond the
     * bound, or this thread does when the family is over its cap again. Answers first: a waiter's answer does not wait
     * for the wake of a thread whose work cannot change it.
     */
    private fun evictBeyond() {
        val families = context.families
        val family = families.firstOrNull { it.spec.kind == key.kind } ?: return
        // Most load ends leave both kinds within their bounds: then there is no pass to run or to queue.
        if (family.count(absent = false).get() <= family.bound(absent = false) &&
            family.count(absent = true).get() <= family.bound(absent = true)
        ) {
            return
        }
        if (families.overCap(family)) {
            DrainBudget.unbounded { families.evictBeyond(family) }
        } else {
            // The line is the key's, built only if housekeeping refuses the pass: a load's end allocates nothing for it.
            context.threads.offHousekeeping(HousekeepingTask.EVICT, key) { families.evictBeyond(family) }
        }
    }
}
