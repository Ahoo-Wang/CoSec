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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import me.ahoo.cosec.runtime.snapshot.SnapshotSource.Companion.isFormatFailure
import me.ahoo.cosec.runtime.snapshot.SnapshotSource.Companion.unwrap
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/** One key: its installed entry and its single in-flight load. */
// B2b: the slot's transitions leave it
@Suppress("TooManyFunctions")
internal class Slot<T : Any>(
    val key: SnapshotKey,
    private val compiler: SnapshotCompiler<T>,
    private val context: SlotContext,
) {
    private val report = CompileReport(key, compiler, context.threads::offRequestThread)

    @Volatile
    var installed: SnapshotEntry<T>? = null
        private set

    /**
     * The LRU stamp. A plain field: every hit writes it, and eviction only needs an approximate order, so it does
     * without the memory barrier a volatile write would cost on the hot path.
     */
    var lastAccess: Long = context.ticker.asLong
        private set

    // Guarded by this.
    private var inFlight: Load? = null
    private var dirty = false
    private var forceNext = false
    private var next: CompletableFuture<SnapshotEntry<T>>? = null

    /** The [compileEpoch] the load that installed [installed] started in. */
    private var installedEpoch = 0L

    /**
     * Set, under this slot's lock and together with dropping the slot from the map, when the slot is evicted or
     * forgotten. Volatile because readers that found the slot before that check it without the lock: a removed
     * slot gets no notifications and no revalidation, so its entry must never be served again.
     */
    @Volatile
    var removed = false
        private set

    /** The delay before the next retry of a reload the compile queue rejected; doubles while it keeps rejecting. */
    @Volatile
    private var retryDelay: Duration = context.options.rejectedReloadRetry

    /** One read of the store and what follows it. Finishes exactly once. */
    inner class Load(
        val known: SnapshotEntry<T>?,
        val force: Boolean,
        val result: CompletableFuture<SnapshotEntry<T>>,
        /** The [compileEpoch] this load started in: it compiles with the inputs of that epoch or a later one. */
        val epoch: Long,
        /** Whether [known] was compiled in an earlier epoch: this load reads in full and must not keep it. */
        val recompiles: Boolean,
        /** The read of the periodic pass that started this load, ended once the read has answered or timed out. */
        val passRead: PassRead?,
    ) {
        val startedAt: Instant = context.clock.instant()

        /** When this load started, in [nanoTime]: what a periodic pass measures its age by. */
        val startedNanos: Long = context.nanoTime.get().asLong

        /** Whether the read may have been answered from a local copy rather than the store of record. */
        var fromLocalCopy = false

        /** The digest the conditional read sent; null for an unconditional read or "I believe it absent". */
        var askedDigest: SnapshotDigest? = null

        private val finished = AtomicBoolean()

        /**
         * When what this load read is known to have been current: the start of the read, or for a local copy the
         * oldest that copy can be, so that only a conditional read can make it look fresh.
         */
        val confirmedAt: Instant
            get() = if (fromLocalCopy) startedAt.minus(context.store.localCopyMaxAge) else startedAt

        fun claim(): Boolean = finished.compareAndSet(false, true)

        val isFinished: Boolean get() = finished.get()
    }

    fun touch() {
        lastAccess = context.ticker.asLong
    }

    /** A load starting now; under this slot's lock. */
    private fun newLoad(
        force: Boolean,
        result: CompletableFuture<SnapshotEntry<T>>,
        passRead: PassRead? = null,
    ): Load {
        val epoch = context.compileEpoch.get()
        val known = installed
        // Only a compiled entry depends on the compiler's inputs; the shared document is not recompiled.
        val compiled = known != null && known.origin.compiled
        val recompiles = key != context.sharedKey && compiled && installedEpoch < epoch
        return Load(known, force, result, epoch, recompiles, passRead)
    }

    /**
     * For [recompile], after the epoch moved: a load in flight is followed by another, which starts in the new
     * epoch. True when this slot is idle with a compiled entry, which the caller then loads again.
     */
    fun markRecompile(): Boolean = synchronized(this) {
        if (removed) {
            return false
        }
        if (inFlight != null) {
            dirty = true
            return false
        }
        val known = installed ?: return false
        known.origin.compiled
    }

    fun isIdle(): Boolean = synchronized(this) { inFlight == null }

    /**
     * Marks this slot removed and drops it from the map, unless a load is in flight or, with [onlyIfEmpty], a
     * document is installed. The checks and the mark happen under this slot's lock.
     */
    fun removeIfIdle(onlyIfEmpty: Boolean): Boolean {
        // The shared slot is never removed.
        val family = context.families.firstOrNull { it.spec.kind == key.kind } ?: return false
        synchronized(this) {
            val holdsDocument = onlyIfEmpty && installed != null
            if (inFlight != null || removed || holdsDocument) {
                return false
            }
            removed = true
            family.drop(family.spec.idOf(key), this)
        }
        return true
    }

    /** The installed entry if it is usable, otherwise a load; null when this slot was removed. */
    fun usable(): CompletableFuture<SnapshotEntry<T>>? {
        if (removed) {
            return null
        }
        val entry = installed
        if (entry != null && entry.state !is DocumentState.Failed) {
            return CompletableFuture.completedFuture(entry)
        }
        return load()
    }

    /**
     * What one request waits for: the installed entry, or its own view of the shared load, bounded in time.
     * Null when this slot was removed.
     */
    fun forRequest(): CompletableFuture<SnapshotEntry<T>>? {
        if (removed) {
            return null
        }
        installed?.let {
            touch()
            context.metrics.onLoad(key.kind, LoadOutcome.HIT)
            return CompletableFuture.completedFuture(it)
        }
        val shared = load() ?: return null
        val own = RequestView()
        context.pendingRequests.add(own)
        if (context.closed.get()) {
            own.abort(context.closedError())
            return own
        }
        val timeout = if (shared.isDone) null else scheduleTimeout(own)
        shared.whenComplete { entry, error ->
            val result = entry ?: transientFailure(context.clock.instant(), unwrap(error))
            val outcome = if (result.state is DocumentState.Failed) LoadOutcome.FAILED else LoadOutcome.COLD
            if (own.answer(result, outcome)) {
                timeout?.cancel(false)
            }
        }
        return own
    }

    /**
     * One request's future. Whoever answers first (the shared load, the timeout, or [close]) claims it; an answer
     * records the outcome before completing, so a caller that sees the result also sees the metric.
     */
    inner class RequestView : CompletableFuture<SnapshotEntry<T>>() {
        private val answered = AtomicBoolean()

        fun answer(entry: SnapshotEntry<T>, outcome: LoadOutcome): Boolean {
            if (!answered.compareAndSet(false, true)) {
                return false
            }
            context.pendingRequests.remove(this)
            try {
                context.metrics.onLoad(key.kind, outcome)
            } finally {
                complete(entry)
            }
            return true
        }

        fun abort(error: Throwable) {
            if (answered.compareAndSet(false, true)) {
                context.pendingRequests.remove(this)
                completeExceptionally(error)
            }
        }
    }

    private fun scheduleTimeout(own: RequestView): ScheduledFuture<*>? {
        val timeout = context.options.coldLoadTimeout
        val onTimeout = Runnable {
            val error = SnapshotUnavailableException(key, "not loaded within $timeout", TimeoutException())
            own.answer(failedEntry(error, context.clock.instant()), LoadOutcome.TIMEOUT)
        }
        return try {
            context.threads.scheduler.schedule(onTimeout, timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (rejected: RejectedExecutionException) {
            own.answer(transientFailure(context.clock.instant(), rejected), LoadOutcome.FAILED)
            null
        }
    }

    /**
     * Starts a load unless one is in flight, in which case it returns that one. Null when this slot was removed;
     * after [close], an already failed entry. A load that [pass] starts counts as a read of it until the read ends.
     *
     * A [pass] does not wait for a load that has been in flight longer than [SnapshotOptions.storeReadTimeout],
     * whoever started it, and gets null as for a removed slot: the store's answer is in by then, so the load is held
     * by a compilation, and confirms the document when it ends. Waiting would stop the pass behind it, and the
     * documents after it would never be revalidated once as many compilations as there are lanes never end (`SNP-6`).
     */
    fun load(force: Boolean = false, pass: Pass? = null): CompletableFuture<SnapshotEntry<T>>? {
        val load: Load
        synchronized(this) {
            if (removed) {
                return null
            }
            if (context.closed.get()) {
                return CompletableFuture.completedFuture(failedEntry(context.closedError(), context.clock.instant()))
            }
            inFlight?.let {
                if (pass != null && context.nanoTime.get().asLong - it.startedNanos > context.options.storeReadTimeout.toNanos()) {
                    return null
                }
                return it.result
            }
            load = newLoad(force, CompletableFuture(), pass?.startRead())
            inFlight = load
            dirty = false
        }
        read(load)
        return load.result
    }

    /**
     * Loads again even if a load is in flight, since a change may postdate what that load reads. Returns the future
     * of the load that starts after the current one. Null when this slot was removed.
     */
    fun reload(force: Boolean): CompletableFuture<SnapshotEntry<T>>? {
        synchronized(this) {
            if (removed) {
                return null
            }
            if (inFlight != null) {
                dirty = true
                forceNext = forceNext || force
                return next ?: CompletableFuture<SnapshotEntry<T>>().also { next = it }
            }
        }
        return load(force)
    }

    /**
     * For [close], with [closed] already set, so no load starts after this looks. What is installed stays, as for a
     * read that failed, and is what this load's waiters get, unless a [recompile] has outdated it.
     */
    fun endLoad() {
        val load = synchronized(this) { inFlight } ?: return
        val outdated = load.recompiles || load.epoch < context.compileEpoch.get()
        finish(
            load,
            load.known.takeUnless { outdated } ?: failedEntry(context.closedError(), context.clock.instant()),
            install = false
        )
    }

    /** A change notification: reload if this key is installed or loading, otherwise there is nothing to refresh. */
    fun onChanged() {
        synchronized(this) {
            if (removed) {
                return
            }
            if (inFlight != null) {
                dirty = true
                return
            }
            if (installed == null) {
                return
            }
        }
        load()
    }

    private fun read(load: Load) {
        guarded(load) {
            val reading = readFor(load)
            withTimeout(reading).whenComplete { read, error ->
                load.passRead?.end()
                // Closing may have ended this load already; what the read found then has nowhere to go.
                if (!load.isFinished) {
                    guarded(load) { onRead(load, read, error) }
                }
            }
        }
    }

    /** Chooses the read: see the class comment of [SnapshotSource] for why seen keys never use a local copy. */
    private fun readFor(load: Load): CompletableFuture<SnapshotRead> {
        val known = load.known
        if (known == null && !context.families.seen.containsKey(key)) {
            load.fromLocalCopy = true
            return context.store.read(key)
        }
        val digest = when {
            known == null || load.force || load.recompiles -> null
            known.origin.compilesAgain -> null
            else -> known.digest
        }
        load.askedDigest = digest
        return context.store.readIfChanged(key, digest)
    }

    /** [reading], failed after [SnapshotOptions.storeReadTimeout] if the store never answers. */
    private fun withTimeout(reading: CompletableFuture<SnapshotRead>): CompletableFuture<SnapshotRead> {
        if (reading.isDone) {
            return reading
        }
        val bounded = CompletableFuture<SnapshotRead>()
        val timeout = try {
            context.threads.scheduler.schedule(
                {
                    bounded.completeExceptionally(
                        TimeoutException("The store did not answer within ${context.options.storeReadTimeout}.")
                    )
                },
                context.options.storeReadTimeout.toNanos(),
                TimeUnit.NANOSECONDS,
            )
        } catch (rejected: RejectedExecutionException) {
            log.debug { "No store read timeout: $rejected" }
            null
        }
        reading.whenComplete { read, error ->
            timeout?.cancel(false)
            if (error != null) bounded.completeExceptionally(error) else bounded.complete(read)
        }
        return bounded
    }

    private fun onRead(load: Load, read: SnapshotRead?, error: Throwable?) {
        if (error != null) {
            val cause = unwrap(error)
            if (cause.isFormatFailure()) onUndecodable(load, cause) else onTransientFailure(load, cause)
            return
        }
        when (read) {
            is SnapshotRead.Present -> {
                context.families.seen.putIfAbsent(key, Seen(read.document.revision, read.digest))
                compileOffThread(load, read)
            }
            SnapshotRead.Absent -> onAbsent(load)
            SnapshotRead.Unchanged -> onUnchanged(load)
            null -> onTransientFailure(load, IllegalStateException("The store answered null."))
        }
    }

    private fun onUnchanged(load: Load) {
        val known = load.known
        when {
            load.fromLocalCopy -> {
                onTransientFailure(load, IllegalStateException("Unchanged answer to an unconditional read."))
            }
            load.askedDigest == null -> onAbsent(load)
            known == null -> {
                onTransientFailure(load, IllegalStateException("Unchanged answer without a known digest."))
            }
            else -> {
                known.confirm(load.confirmedAt)
                finish(load, known, install = false)
            }
        }
    }

    private fun onUndecodable(load: Load, cause: Throwable) {
        context.families.seen.putIfAbsent(key, Seen(0, null))
        val known = load.known
        if (known != null && known.origin == EntryOrigin.UNDECODABLE) {
            known.confirm(load.confirmedAt)
            finish(load, known, install = false)
            return
        }
        val failure = SnapshotUnavailableException(key, "the stored document cannot be decoded", cause)
        finish(load, entryOf(load, DocumentState.Failed(failure), EntryOrigin.UNDECODABLE), install = true)
    }

    private fun onAbsent(load: Load) {
        val known = load.known
        if (known != null && known.origin.storedAbsent) {
            known.confirm(load.confirmedAt)
            finish(load, known, install = false)
            return
        }
        val lastSeen = context.families.seen[key]
        if (lastSeen == null) {
            finish(load, entryOf(load, DocumentState.Absent, EntryOrigin.ABSENT), install = true)
            return
        }
        val error = SnapshotUnavailableException(
            key,
            "the store has no document, but revision ${lastSeen.revision} was seen; it may have lost data",
        )
        finish(load, entryOf(load, DocumentState.Failed(error), EntryOrigin.LOST), install = true)
        // After the install: a throwing meter must not keep the lost document in service.
        context.threads.offRequestThread {
            log.error(error) { "Snapshot document [$key] disappeared from the store; failing closed." }
        }
        context.metrics.onDocumentLost(key, lastSeen.revision)
    }

    private fun compileOffThread(load: Load, read: SnapshotRead.Present) {
        try {
            context.threads.compileExecutor.execute {
                guarded(load) { compileAndInstall(load, read) }
            }
        } catch (rejected: RejectedExecutionException) {
            onTransientFailure(
                load,
                SnapshotUnavailableException(key, "the compile queue is full", rejected),
                rejected = true
            )
        }
    }

    /**
     * A reload that found the compile queue full tries again, with a delay that doubles while the queue stays full,
     * up to the revalidation interval.
     */
    private fun retryLater() {
        val delay = retryDelay
        retryDelay = minOf(delay.multipliedBy(2), context.options.revalidateInterval)
        try {
            context.threads.scheduler.schedule({ reload(force = false) }, delay.toNanos(), TimeUnit.NANOSECONDS)
        } catch (rejected: RejectedExecutionException) {
            log.debug { "No reload retry: $rejected" }
        }
    }

    private fun compileAndInstall(load: Load, read: SnapshotRead.Present) {
        val document = read.document
        val begin = System.nanoTime()
        val state = try {
            compileState(document)
        } catch (error: VirtualMachineError) {
            // RC1-43: a document that compiles on the control plane can overflow a smaller data-plane stack. Ending
            // the load as a transient failure would keep the previous revision, and a new DENY would never apply.
            val failure = SnapshotUnavailableException(key, report.failedToCompile(document.revision), error)
            val failed = SnapshotEntry(
                key,
                DocumentState.Failed(failure),
                document.revision,
                read.digest,
                load.confirmedAt,
                EntryOrigin.UNCOMPILABLE,
            )
            finish(load, failed, install = true)
            throw error
        }
        retryDelay = context.options.rejectedReloadRetry
        context.metrics.onCompile(key.kind, Duration.ofNanos(System.nanoTime() - begin), state is DocumentState.Present)
        val origin = if (state is DocumentState.Present) EntryOrigin.DOCUMENT else EntryOrigin.UNCOMPILABLE
        val entry = SnapshotEntry(key, state, document.revision, read.digest, load.confirmedAt, origin)
        finish(load, entry, install = true)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun compileState(document: SnapshotDocument): DocumentState<T> = try {
        DocumentState.Present(compiler.compile(key, document))
    } catch (error: VirtualMachineError) {
        throw error
    } catch (error: Throwable) {
        val failure = SnapshotUnavailableException(key, report.failedToCompile(document.revision), error)
        DocumentState.Failed(failure)
    }

    /**
     * The store could not be read: keep what is installed (F6), or answer failed without installing (F1). An entry
     * compiled with inputs that have changed since is not kept: it fails closed until a retry compiles it again.
     * A reload [rejected] by the compile queue retries, and so does one that keeps an entry that failed to compile
     * (it may only be waiting for a recompile), however the inputs changed, until a load compiles it.
     */
    private fun onTransientFailure(load: Load, cause: Throwable, rejected: Boolean = false) {
        val known = load.known
        if (known != null && load.recompiles) {
            context.threads.offRequestThread {
                log.warn(
                    cause
                ) { "Compiling snapshot document [$key] again failed; failing closed until it compiles." }
            }
            val error = SnapshotUnavailableException(
                key,
                "compiled with inputs that changed, and not compiled again",
                cause
            )
            val failed = SnapshotEntry(
                key,
                DocumentState.Failed(error),
                known.revision,
                known.digest,
                known.confirmedAt,
                EntryOrigin.RECOMPILE_PENDING
            )
            finish(load, failed, install = true)
            retryLater()
            return
        }
        if (known != null) {
            context.threads.offRequestThread {
                log.warn(cause) { "Reloading snapshot document [$key] failed; keeping revision ${known.revision}." }
            }
            finish(load, known, install = false)
            if (rejected || known.origin.compilesAgain) {
                retryLater()
            }
            return
        }
        context.threads.offRequestThread { log.warn(cause) { "Loading snapshot document [$key] failed." } }
        finish(load, transientFailure(load.startedAt, cause), install = false)
    }

    /**
     * Ends [load] once. The install is recorded (metrics first, then visible to [peek]); then, whatever the metrics
     * did, the state changes: the in-flight load is cleared and, if a change arrived meanwhile, the next load is
     * created under the same lock, so no other load and no removal can come between them. The load's future
     * completes last. Eviction and dropping an empty slot run afterwards on the housekeeping thread.
     *
     * A family entry compiled in an earlier [compileEpoch] may have compiled with inputs that [recompile] replaced:
     * another load follows at once, and a compiled document is not installed. Its waiters get what that load
     * installs, or fail by their own timeout, never the old compilation.
     */
    private fun finish(load: Load, entry: SnapshotEntry<T>, install: Boolean) {
        if (!load.claim()) {
            if (!context.closed.get()) {
                log.error { "Snapshot load of [$key] tried to finish twice; ignored." }
            }
            return
        }
        // Whatever ended the load (a read that failed to start included), its read is over.
        load.passRead?.end()
        val stale = install && key != context.sharedKey && load.epoch < context.compileEpoch.get() && entry.origin.compiled
        val withheld = stale && entry.origin == EntryOrigin.DOCUMENT
        try {
            if (install && !withheld) {
                recordInstall(entry)
                report.installed(entry)
            }
        } finally {
            advance(load, entry, install && !withheld, stale, withheld)
        }
    }

    private fun advance(load: Load, entry: SnapshotEntry<T>, install: Boolean, stale: Boolean, withheld: Boolean) {
        val nextLoad: Load?
        val unanswered: CompletableFuture<SnapshotEntry<T>>?
        val empty: Boolean
        synchronized(this) {
            if (install) {
                installed = entry
                installedEpoch = load.epoch
            }
            if (inFlight === load) {
                inFlight = null
            }
            val wanted = dirty || stale || (install && load.fromLocalCopy)
            val pending = next
            val open = !context.closed.get() && !removed
            nextLoad = if (wanted && inFlight == null && open) {
                newLoad(forceNext, pending ?: CompletableFuture()).also { inFlight = it }
            } else {
                null
            }
            unanswered = if (nextLoad == null) pending else null
            dirty = false
            forceNext = false
            next = null
            empty = installed == null
        }
        val answer = answerFor(entry, withheld, nextLoad)
        try {
            startOrAnswer(nextLoad, unanswered, answer)
        } finally {
            housekeep(empty, install)
            answerWaiters(load, answer, nextLoad.takeIf { withheld })
        }
    }

    /** [entry], unless it is withheld and no load follows to compile it again (the source is closing): a failure. */
    private fun answerFor(entry: SnapshotEntry<T>, withheld: Boolean, nextLoad: Load?): SnapshotEntry<T> =
        if (withheld && nextLoad == null) {
            failedEntry(SnapshotUnavailableException(key, "compiled with inputs that changed"), context.clock.instant())
        } else {
            entry
        }

    /** Completes [load]'s future with [answer], or with what [recompiling] installs in place of a withheld entry. */
    private fun answerWaiters(load: Load, answer: SnapshotEntry<T>, recompiling: Load?) {
        if (recompiling == null) {
            load.result.complete(answer)
            return
        }
        recompiling.result.whenComplete { next, error ->
            load.result.complete(next ?: transientFailure(context.clock.instant(), unwrap(error)))
        }
    }

    /** Starts [nextLoad], or answers a pending [reload] future with [entry] when no load could follow. */
    private fun startOrAnswer(
        nextLoad: Load?,
        unanswered: CompletableFuture<SnapshotEntry<T>>?,
        entry: SnapshotEntry<T>,
    ) {
        if (nextLoad != null) {
            read(nextLoad)
        } else {
            unanswered?.complete(entry)
        }
    }

    private fun housekeep(empty: Boolean, install: Boolean) {
        if (empty) {
            context.threads.offRequestThread { removeIfIdle(onlyIfEmpty = true) }
        } else if (install) {
            context.families.firstOrNull { it.spec.kind == key.kind }?.let { family ->
                context.threads.offRequestThread {
                    context.families.evictBeyond(
                        family
                    )
                }
            }
        }
    }

    private fun recordInstall(entry: SnapshotEntry<T>) {
        val regressedFrom = synchronized(this) { context.families.recordSeen(entry) }
        log.debug { "Installed $entry." }
        if (regressedFrom != null) {
            context.threads.offRequestThread {
                log.error {
                    "Snapshot document [$key] regressed from revision ${regressedFrom.revision} to ${entry.revision}; " +
                        "the store may have lost a write (for example a Redis failover). Installing what the store holds."
                }
            }
        }
        try {
            context.metrics.onInstall(entry)
        } finally {
            if (regressedFrom != null) {
                context.metrics.onRegression(key, regressedFrom.revision, entry.revision)
            }
        }
    }

    /** An entry with no decoded document behind it: what the store answered, as of [load]'s confirmation time. */
    private fun entryOf(load: Load, state: DocumentState<T>, origin: EntryOrigin): SnapshotEntry<T> =
        SnapshotEntry(key, state, 0, null, load.confirmedAt, origin)

    private fun transientFailure(at: Instant, cause: Throwable): SnapshotEntry<T> {
        val error = cause as? SnapshotUnavailableException ?: SnapshotUnavailableException(key, "the store cannot be read", cause)
        return failedEntry(error, at)
    }

    private fun failedEntry(error: Throwable, at: Instant): SnapshotEntry<T> =
        SnapshotEntry(key, DocumentState.Failed(error), 0, null, at, EntryOrigin.TRANSIENT)

    /**
     * Runs [block]; anything it throws before [load] finishes ends the load as a transient failure, so the slot is
     * never left with a load that never finishes. A [VirtualMachineError] is rethrown after that.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(load: Load, block: () -> Unit) {
        try {
            block()
        } catch (error: VirtualMachineError) {
            endAfter(load, error)
            throw error
        } catch (error: Throwable) {
            endAfter(load, error)
        }
    }

    private fun endAfter(load: Load, error: Throwable) {
        if (load.isFinished) {
            log.error(error) { "Snapshot document [$key] failed after its load finished." }
        } else {
            onTransientFailure(load, error)
        }
    }
}
