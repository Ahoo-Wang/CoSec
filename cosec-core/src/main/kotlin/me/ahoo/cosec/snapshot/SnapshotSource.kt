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

package me.ahoo.cosec.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.context.request.AppId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier

/**
 * Holds the compiled policy snapshot (RFC 0003 §4.2–§4.5): the shared [SnapshotKey.Policies] document, which is never
 * evicted, and up to [SnapshotOptions.maxApps] [SnapshotKey.App] documents, evicted least recently used.
 *
 * - [peek] is the hot path: a synchronous in-memory lookup with no future and no I/O, safe on an event loop.
 * - [load] reads and compiles what is missing. The store is read on the caller's thread (stores never block), every
 *   compilation runs on a bounded compile pool, and concurrent loads of one key share a single read and compilation.
 * - Installs of one key happen in the order their loads started: at most one load per key is in flight, and a change
 *   notification that arrives during it starts another load once it completes.
 * - Installed documents are revalidated every [SnapshotOptions.revalidateInterval] with a conditional read, so a
 *   document's lag (time since the store last confirmed it) is bounded while the store is reachable.
 *
 * Failures keep 7.0's semantics (RFC 0003 §4.4): a read that fails while a document is installed keeps it (F6, bounded
 * by lag and [checkFresh]); a document that is present but cannot be decoded or compiled is installed as
 * [DocumentState.Failed] (F3, F5); a cold read that fails is answered as failed and not installed (F1).
 *
 * Revisions are metadata, not a guard. Redis replicates asynchronously, so a failover can promote a replica that never
 * received an acknowledged write, and the store then answers with an older revision than the one installed. The older
 * document is installed anyway, because the store is the record and every other instance reads the same, and the
 * regression is reported through [SnapshotMetrics.onRegression] (F11).
 *
 * Once a key has been seen with a document, `Absent` for it is an error, not "no policies": no operation deletes a
 * snapshot document, so its disappearance means the store lost data, and the key fails closed until a document returns.
 * The memory of seen keys survives eviction and lasts as long as this source.
 */
class SnapshotSource<P : Any, A : Any> internal constructor(
    private val store: SnapshotDocumentStore,
    policiesCompiler: SnapshotCompiler<P>,
    private val appCompiler: SnapshotCompiler<A>,
    private val options: SnapshotOptions,
    private val clock: Clock,
    private val metrics: SnapshotMetrics,
    compileExecutor: Executor?,
    scheduler: ScheduledExecutorService?,
    private val ticker: LongSupplier,
) : AutoCloseable {

    constructor(
        store: SnapshotDocumentStore,
        policiesCompiler: SnapshotCompiler<P>,
        appCompiler: SnapshotCompiler<A>,
        options: SnapshotOptions = SnapshotOptions(),
        clock: Clock = Clock.systemUTC(),
        metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
    ) : this(store, policiesCompiler, appCompiler, options, clock, metrics, null, null, System::nanoTime)

    private val ownsCompileExecutor = compileExecutor == null
    private val compileExecutor: Executor = compileExecutor ?: newCompileExecutor(options)
    private val ownsScheduler = scheduler == null
    private val scheduler: ScheduledExecutorService = scheduler ?: newScheduler()

    private val policies = Slot(SnapshotKey.Policies, policiesCompiler)
    private val apps = ConcurrentHashMap<AppId, Slot<A>>()
    private val evictionLock = Any()

    /** The last decoded revision installed per key, kept across eviction (see the class comment). */
    private val seen = ConcurrentHashMap<SnapshotKey, Seen>()
    private val lastLagLog = ConcurrentHashMap<SnapshotKey, Instant>()
    private val pendingPrewarms: MutableSet<CompletableFuture<Void>> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var closed = false

    @Volatile
    private var prewarmed = false

    private val subscription: AutoCloseable = store.subscribe(ChangeListener())
    private val periodic: ScheduledFuture<*>? = schedulePeriodic()

    /** Whether a [prewarm] has completed: until then the instance should not report ready (RFC 0003 §4.3). */
    val isPrewarmed: Boolean get() = prewarmed

    /**
     * The installed snapshot for a request that needs the app document of [appId] (or none when null), or null when a
     * needed document is not installed. Never does I/O and never allocates when [appId] is null.
     *
     * A document that is installed but failed is returned as such: the caller fails the layers it feeds.
     */
    fun peek(appId: AppId? = null): Snapshot<P, A>? {
        val policiesEntry = policies.installed ?: return null
        if (appId == null) {
            metrics.onLoad(SnapshotKind.POLICIES, LoadOutcome.HIT)
            return policiesEntry.alone
        }
        val slot = apps[appId] ?: return null
        val appEntry = slot.installed ?: return null
        slot.touch()
        metrics.onLoad(SnapshotKind.POLICIES, LoadOutcome.HIT)
        metrics.onLoad(SnapshotKind.APP, LoadOutcome.HIT)
        return Snapshot(policiesEntry, appEntry)
    }

    /**
     * The snapshot for a request that needs the app document of [appId] (or none when null), loading what is missing.
     * Never blocks the caller. Each caller gets its own future, so cancelling it leaves the shared load running.
     *
     * The future completes normally even when a document cannot be loaded: that document is [DocumentState.Failed],
     * including when it is not ready within [SnapshotOptions.coldLoadTimeout] (the load goes on in the background).
     * It completes exceptionally only after [close].
     *
     * @throws IllegalArgumentException when [appId] is not a valid app id; check [SnapshotKey.App.isValidAppId] first.
     */
    fun load(appId: AppId? = null): CompletableFuture<Snapshot<P, A>> {
        val appKey = appId?.let { SnapshotKey.App(it) }
        if (closed) {
            return CompletableFuture.failedFuture(IllegalStateException("SnapshotSource is closed."))
        }
        val policiesEntry = policies.forRequest()
        if (appKey == null) {
            return policiesEntry.thenApply { it.alone }
        }
        val appEntry = appSlot(appKey).forRequest()
        return policiesEntry.thenCombine(appEntry) { p, a -> Snapshot(p, a) }
    }

    /**
     * Loads [keys] and completes once every one of them is installed and not failed, retrying with backoff from
     * [SnapshotOptions.prewarmInitialBackoff] up to [SnapshotOptions.prewarmMaxBackoff]. [isPrewarmed] turns true then.
     */
    fun prewarm(keys: Collection<SnapshotKey>): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        pendingPrewarms.add(done)
        done.whenComplete { _, _ -> pendingPrewarms.remove(done) }
        attemptPrewarm(keys.toList(), options.prewarmInitialBackoff, done)
        return done
    }

    /**
     * Throws [SnapshotStaleException] when [tenantId] is configured to fail closed and [snapshot] lags more than
     * [SnapshotOptions.failClosedMaxLag]. Other tenants are never affected (RFC 0003 §4.5).
     */
    fun checkFresh(snapshot: Snapshot<*, *>, tenantId: String) {
        if (options.failClosedTenants.isEmpty() || tenantId !in options.failClosedTenants) {
            return
        }
        val lag = snapshot.lagAt(clock.instant())
        if (lag > options.failClosedMaxLag) {
            throw SnapshotStaleException(tenantId, lag, options.failClosedMaxLag)
        }
    }

    /** Every installed entry: for gauges and the actuator endpoint. */
    fun entries(): List<SnapshotEntry<*>> = buildList {
        policies.installed?.let { add(it) }
        apps.values.forEach { slot -> slot.installed?.let { add(it) } }
    }

    /** Reloads [key] on this instance only, whether or not it is installed. */
    fun reload(key: SnapshotKey): CompletableFuture<SnapshotEntry<*>> = when (key) {
        SnapshotKey.Policies -> policies.reload().thenApply<SnapshotEntry<*>> { it }
        is SnapshotKey.App -> appSlot(key).reload().thenApply<SnapshotEntry<*>> { it }
    }

    /** Revalidates every installed document with a conditional read, a few at a time. */
    fun revalidate(): CompletableFuture<Void> = reloadAll(installedSlots()) { it.load() }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        periodic?.cancel(false)
        subscription.close()
        if (ownsScheduler) {
            scheduler.shutdownNow()
        }
        pendingPrewarms.forEach { it.completeExceptionally(IllegalStateException("SnapshotSource is closed.")) }
        if (ownsCompileExecutor) {
            (compileExecutor as ThreadPoolExecutor).shutdown()
        }
    }

    /** One periodic pass: report lag, then revalidate. */
    internal fun tick(): CompletableFuture<Void> {
        reportLag()
        return revalidate()
    }

    internal fun reportLag() {
        val now = clock.instant()
        for (entry in entries()) {
            if (entry.state is DocumentState.Failed) {
                continue
            }
            val lag = entry.lagAt(now)
            val escalated = lag > options.lagEscalate
            if (!escalated && lag <= options.lagWarn) {
                lastLagLog.remove(entry.key)
                continue
            }
            metrics.onLagExceeded(entry.key, lag, escalated)
            val last = lastLagLog[entry.key]
            if (last != null && Duration.between(last, now) < options.lagLogInterval) {
                continue
            }
            lastLagLog[entry.key] = now
            if (escalated) {
                log.error { "Snapshot document [${entry.key}] revision ${entry.revision} is stale: not confirmed for $lag." }
            } else {
                log.warn { "Snapshot document [${entry.key}] revision ${entry.revision} has not been confirmed for $lag." }
            }
        }
    }

    private fun schedulePeriodic(): ScheduledFuture<*>? {
        if (options.revalidateInterval.isZero) {
            return null
        }
        val millis = options.revalidateInterval.toMillis()
        return scheduler.scheduleWithFixedDelay(::periodicTick, millis, millis, TimeUnit.MILLISECONDS)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun periodicTick() {
        try {
            tick()
        } catch (error: Throwable) {
            log.error(error) { "Snapshot revalidation pass failed." }
        }
    }

    private fun appSlot(key: SnapshotKey.App): Slot<A> = apps.computeIfAbsent(key.appId) { Slot(key, appCompiler) }

    private fun slotOf(key: SnapshotKey): Slot<*> = when (key) {
        SnapshotKey.Policies -> policies
        is SnapshotKey.App -> appSlot(key)
    }

    private fun installedSlots(): List<Slot<*>> = buildList {
        if (policies.installed != null) add(policies)
        apps.values.filterTo(this) { it.installed != null }
    }

    private fun reloadAll(slots: List<Slot<*>>, reload: (Slot<*>) -> CompletableFuture<*>): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        if (slots.isEmpty()) {
            done.complete(null)
            return done
        }
        val next = AtomicInteger()
        val remaining = AtomicInteger(slots.size)
        fun startNext() {
            val index = next.getAndIncrement()
            if (index >= slots.size) {
                return
            }
            reload(slots[index]).whenComplete { _, _ ->
                if (remaining.decrementAndGet() == 0) {
                    done.complete(null)
                } else {
                    startNext()
                }
            }
        }
        repeat(minOf(options.reloadConcurrency, slots.size)) { startNext() }
        return done
    }

    private fun attemptPrewarm(keys: List<SnapshotKey>, backoff: Duration, done: CompletableFuture<Void>) {
        if (closed) {
            done.completeExceptionally(IllegalStateException("SnapshotSource is closed."))
            return
        }
        val loads = keys.map { slotOf(it).usable() }
        CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, _ ->
            val failed = loads.map { it.join() }.filter { it.state is DocumentState.Failed }
            if (failed.isEmpty()) {
                prewarmed = true
                done.complete(null)
                return@whenComplete
            }
            log.warn { "Snapshot prewarm failed for ${failed.map { it.key }}; retrying in $backoff." }
            val nextBackoff = minOf(backoff.multipliedBy(2), options.prewarmMaxBackoff)
            try {
                scheduler.schedule(
                    { attemptPrewarm(keys, nextBackoff, done) },
                    backoff.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            } catch (rejected: RejectedExecutionException) {
                done.completeExceptionally(rejected)
            }
        }
    }

    private fun evictApps() {
        if (apps.size <= options.maxApps) {
            return
        }
        synchronized(evictionLock) {
            while (apps.size > options.maxApps) {
                val victim = apps.values.filter { it.installed != null && it.isIdle() }.minByOrNull { it.lastAccess } ?: return
                apps.remove((victim.key as SnapshotKey.App).appId, victim)
                log.debug { "Evicted snapshot document [${victim.key}]." }
            }
        }
    }

    /** Registers the decoded revision of [entry]; returns the previous one when [entry] regresses from it. */
    private fun recordSeen(entry: SnapshotEntry<*>): Seen? {
        if (entry.revision == 0L) {
            return null
        }
        val previous = seen.put(entry.key, Seen(entry.revision, entry.digest)) ?: return null
        val regressed = entry.revision < previous.revision ||
            (entry.revision == previous.revision && entry.digest != previous.digest)
        return previous.takeIf { regressed }
    }

    private class Seen(val revision: Long, val digest: SnapshotDigest?)

    /** One key: its installed entry and its single in-flight load. */
    private inner class Slot<T : Any>(val key: SnapshotKey, private val compiler: SnapshotCompiler<T>) {
        @Volatile
        var installed: SnapshotEntry<T>? = null
            private set

        @Volatile
        var lastAccess: Long = ticker.asLong
            private set

        // Guarded by this.
        private var inFlight: CompletableFuture<SnapshotEntry<T>>? = null
        private var dirty = false

        fun touch() {
            lastAccess = ticker.asLong
        }

        fun isIdle(): Boolean = synchronized(this) { inFlight == null }

        /** The installed entry if it is usable, otherwise a load. */
        fun usable(): CompletableFuture<SnapshotEntry<T>> {
            val entry = installed
            if (entry != null && entry.state !is DocumentState.Failed) {
                return CompletableFuture.completedFuture(entry)
            }
            return load()
        }

        /** What one request waits for: the installed entry, or its own view of the shared load, bounded in time. */
        fun forRequest(): CompletableFuture<SnapshotEntry<T>> {
            installed?.let {
                metrics.onLoad(key.kind, LoadOutcome.HIT)
                return CompletableFuture.completedFuture(it)
            }
            val shared = load()
            val own = CompletableFuture<SnapshotEntry<T>>()
            val timeout = if (shared.isDone) null else scheduleTimeout(own)
            shared.whenComplete { entry, error ->
                val result = entry ?: transientFailure(clock.instant(), unwrap(error))
                if (own.complete(result)) {
                    timeout?.cancel(false)
                    val outcome = if (result.state is DocumentState.Failed) LoadOutcome.FAILED else LoadOutcome.COLD
                    metrics.onLoad(key.kind, outcome)
                }
            }
            return own
        }

        private fun scheduleTimeout(own: CompletableFuture<SnapshotEntry<T>>): ScheduledFuture<*>? {
            val timeout = options.coldLoadTimeout
            val onTimeout = Runnable {
                val error = SnapshotUnavailableException(key, "not loaded within $timeout", TimeoutException())
                if (own.complete(failedEntry(error, clock.instant()))) {
                    metrics.onLoad(key.kind, LoadOutcome.TIMEOUT)
                }
            }
            return try {
                scheduler.schedule(onTimeout, timeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (rejected: RejectedExecutionException) {
                own.complete(transientFailure(clock.instant(), rejected))
                null
            }
        }

        /** Starts a load unless one is in flight, in which case it returns that one. */
        fun load(): CompletableFuture<SnapshotEntry<T>> {
            val started = CompletableFuture<SnapshotEntry<T>>()
            val known: SnapshotEntry<T>?
            synchronized(this) {
                inFlight?.let { return it }
                inFlight = started
                dirty = false
                known = installed
            }
            read(known, started)
            return started
        }

        /** Loads again even if a load is in flight: a change notification may postdate what that load reads. */
        fun reload(): CompletableFuture<SnapshotEntry<T>> {
            synchronized(this) {
                inFlight?.let {
                    dirty = true
                    return it
                }
            }
            return load()
        }

        /** A change notification: reload if this key is installed or loading, otherwise there is nothing to refresh. */
        fun onChanged() {
            synchronized(this) {
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

        private fun read(known: SnapshotEntry<T>?, result: CompletableFuture<SnapshotEntry<T>>) {
            val startedAt = clock.instant()
            guarded(known, startedAt, result) {
                val reading = if (known == null) store.read(key) else store.readIfChanged(key, known.digest)
                reading.whenComplete { read, error ->
                    guarded(known, startedAt, result) { onRead(known, startedAt, read, error, result) }
                }
            }
        }

        private fun onRead(
            known: SnapshotEntry<T>?,
            startedAt: Instant,
            read: SnapshotRead?,
            error: Throwable?,
            result: CompletableFuture<SnapshotEntry<T>>,
        ) {
            if (error != null) {
                val cause = unwrap(error)
                if (cause.isFormatFailure()) {
                    val failure = SnapshotUnavailableException(key, "the stored document cannot be decoded", cause)
                    seen.putIfAbsent(key, Seen(0, null))
                    finish(result, failedEntry(failure, startedAt), install = true)
                } else {
                    onTransientFailure(known, startedAt, cause, result)
                }
                return
            }
            when (read) {
                is SnapshotRead.Present -> compileOffThread(known, startedAt, read, result)
                SnapshotRead.Absent -> onAbsent(known, startedAt, result)
                SnapshotRead.Unchanged -> when {
                    known == null -> onTransientFailure(
                        null,
                        startedAt,
                        IllegalStateException("Unchanged answer to an unconditional read."),
                        result
                    )
                    known.digest == null -> onAbsent(known, startedAt, result)
                    else -> {
                        known.confirm(startedAt)
                        finish(result, known, install = false)
                    }
                }
                null -> onTransientFailure(known, startedAt, IllegalStateException("The store answered null."), result)
            }
        }

        private fun onAbsent(known: SnapshotEntry<T>?, startedAt: Instant, result: CompletableFuture<SnapshotEntry<T>>) {
            if (known != null && known.storedAbsent) {
                known.confirm(startedAt)
                finish(result, known, install = false)
                return
            }
            val lastSeen = seen[key]
            if (lastSeen == null) {
                finish(
                    result,
                    SnapshotEntry(key, DocumentState.Absent, 0, null, startedAt, storedAbsent = true),
                    install = true
                )
                return
            }
            val error = SnapshotUnavailableException(
                key,
                "the store has no document, but revision ${lastSeen.revision} was seen; it may have lost data",
            )
            log.error(error) { "Snapshot document [$key] disappeared from the store; failing closed." }
            metrics.onDocumentLost(key, lastSeen.revision)
            finish(
                result,
                SnapshotEntry(key, DocumentState.Failed(error), 0, null, startedAt, storedAbsent = true),
                install = true
            )
        }

        private fun compileOffThread(
            known: SnapshotEntry<T>?,
            startedAt: Instant,
            read: SnapshotRead.Present,
            result: CompletableFuture<SnapshotEntry<T>>,
        ) {
            try {
                compileExecutor.execute {
                    guarded(known, startedAt, result) { compileAndInstall(startedAt, read, result) }
                }
            } catch (rejected: RejectedExecutionException) {
                onTransientFailure(
                    known,
                    startedAt,
                    SnapshotUnavailableException(key, "the compile queue is full", rejected),
                    result
                )
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun compileAndInstall(
            startedAt: Instant,
            read: SnapshotRead.Present,
            result: CompletableFuture<SnapshotEntry<T>>
        ) {
            val document = read.document
            val begin = System.nanoTime()
            val state: DocumentState<T> = try {
                DocumentState.Present(compiler.compile(key, document))
            } catch (error: Throwable) {
                DocumentState.Failed(
                    SnapshotUnavailableException(key, "revision ${document.revision} cannot be compiled", error)
                )
            }
            metrics.onCompile(key.kind, Duration.ofNanos(System.nanoTime() - begin), state is DocumentState.Present)
            finish(result, SnapshotEntry(key, state, document.revision, read.digest, startedAt), install = true)
        }

        /** The store could not be read: keep what is installed (F6), or answer failed without installing (F1). */
        private fun onTransientFailure(
            known: SnapshotEntry<T>?,
            startedAt: Instant,
            cause: Throwable,
            result: CompletableFuture<SnapshotEntry<T>>,
        ) {
            if (known != null) {
                log.warn(cause) { "Reloading snapshot document [$key] failed; keeping revision ${known.revision}." }
                finish(result, known, install = false)
                return
            }
            log.warn(cause) { "Loading snapshot document [$key] failed." }
            finish(result, transientFailure(startedAt, cause), install = false)
        }

        private fun finish(result: CompletableFuture<SnapshotEntry<T>>, entry: SnapshotEntry<T>, install: Boolean) {
            val regressedFrom: Seen?
            val again: Boolean
            val empty: Boolean
            synchronized(this) {
                regressedFrom = if (install) {
                    installed = entry
                    recordSeen(entry)
                } else {
                    null
                }
                inFlight = null
                again = dirty && !closed
                dirty = false
                empty = installed == null
            }
            if (install) {
                metrics.onInstall(entry)
                log.debug { "Installed $entry." }
            }
            if (regressedFrom != null) {
                log.error {
                    "Snapshot document [$key] regressed from revision ${regressedFrom.revision} to ${entry.revision}; " +
                        "the store may have lost a write (for example a Redis failover). Installing what the store holds."
                }
                metrics.onRegression(key, regressedFrom.revision, entry.revision)
            }
            result.complete(entry)
            if (again) {
                load()
            } else if (empty) {
                forgetIfEmpty()
            } else if (install) {
                evictApps()
            }
        }

        /** Drops an app slot that holds nothing, so failed cold loads of unknown apps leave no trace. */
        private fun forgetIfEmpty() {
            val appKey = key as? SnapshotKey.App ?: return
            synchronized(this) {
                if (installed == null && inFlight == null) {
                    apps.computeIfPresent(appKey.appId) { _, current -> current.takeUnless { it === this } }
                }
            }
        }

        private fun transientFailure(at: Instant, cause: Throwable): SnapshotEntry<T> {
            val error = cause as? SnapshotUnavailableException ?: SnapshotUnavailableException(key, "the store cannot be read", cause)
            return failedEntry(error, at)
        }

        private fun failedEntry(error: Throwable, at: Instant): SnapshotEntry<T> =
            SnapshotEntry(key, DocumentState.Failed(error), 0, null, at)

        /** Runs [block]; anything it throws before [result] completes ends the load as a transient failure. */
        @Suppress("TooGenericExceptionCaught")
        private inline fun guarded(
            known: SnapshotEntry<T>?,
            startedAt: Instant,
            result: CompletableFuture<SnapshotEntry<T>>,
            block: () -> Unit,
        ) {
            try {
                block()
            } catch (error: Throwable) {
                if (result.isDone) {
                    log.error(error) { "Snapshot document [$key] failed after its load completed." }
                } else {
                    onTransientFailure(known, startedAt, error, result)
                }
            }
        }
    }

    private inner class ChangeListener : SnapshotChangeListener {
        override fun onChanged(keys: Collection<SnapshotKey>) {
            for (key in keys) {
                when (key) {
                    SnapshotKey.Policies -> policies.onChanged()
                    is SnapshotKey.App -> apps[key.appId]?.onChanged()
                }
            }
        }

        override fun onReset() {
            log.info { "Snapshot change notifications may have been lost; reloading every installed document." }
            reloadAll(installedSlots()) { it.reload() }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}

        fun unwrap(error: Throwable?): Throwable {
            var current = error ?: return IllegalStateException("The load completed without a result.")
            while (current is CompletionException || current is ExecutionException) {
                current = current.cause ?: return current
            }
            return current
        }

        fun Throwable.isFormatFailure(): Boolean = generateSequence(this) {
            it.cause
        }.take(16).any { it is SnapshotFormatException }

        fun newCompileExecutor(options: SnapshotOptions): ThreadPoolExecutor = ThreadPoolExecutor(
            options.compileThreads,
            options.compileThreads,
            1,
            TimeUnit.MINUTES,
            ArrayBlockingQueue(options.compileQueueCapacity),
            daemonThreads("cosec-snapshot-compile"),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

        fun newScheduler(): ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(1, daemonThreads("cosec-snapshot-scheduler")).apply {
                removeOnCancelPolicy = true
            }

        fun daemonThreads(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
