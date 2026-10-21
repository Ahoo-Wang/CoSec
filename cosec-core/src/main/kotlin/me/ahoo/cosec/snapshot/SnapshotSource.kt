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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier

/**
 * Holds the compiled policy snapshot (RFC 0003 §4.2–§4.5): the shared [SnapshotKey.Policies] document, which is never
 * evicted, and up to [SnapshotOptions.maxApps] [SnapshotKey.App] documents, evicted least recently used.
 *
 * - [peek] is the hot path: a synchronous in-memory lookup with no future and no I/O, safe on an event loop.
 * - [load] reads and compiles what is missing. The store is read on the caller's thread (stores never block), every
 *   compilation runs on a bounded compile pool, and concurrent loads of one key share a single read and compilation.
 * - Installs of one key happen in the order their loads started: at most one load per key is in flight, each load
 *   finishes exactly once, and a change notification that arrives during a load starts another once it completes.
 * - Installed documents are revalidated every [SnapshotOptions.revalidateInterval] with a conditional read of the store
 *   of record, so a document's lag (time since the store last confirmed it) is bounded while the store is reachable.
 *
 * Only a key never seen by this source is cold-loaded with [SnapshotDocumentStore.read], which may answer from a local
 * copy; that install is followed at once by a conditional read that confirms or replaces it. A key seen before (for
 * example one evicted since) is cold-loaded with the conditional read, so an older local copy can never replace what
 * this instance already served.
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
 *
 * [SnapshotMetrics] callbacks that throw are logged and ignored.
 */
class SnapshotSource<P : Any, A : Any> internal constructor(
    private val store: SnapshotDocumentStore,
    policiesCompiler: SnapshotCompiler<P>,
    private val appCompiler: SnapshotCompiler<A>,
    private val options: SnapshotOptions,
    private val clock: Clock,
    metrics: SnapshotMetrics,
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

    private val metrics: SnapshotMetrics = GuardedSnapshotMetrics(metrics)
    private val ownsCompileExecutor = compileExecutor == null
    private val compileExecutor: Executor = compileExecutor ?: newCompileExecutor(options)
    private val ownsScheduler = scheduler == null
    private val scheduler: ScheduledExecutorService = scheduler ?: newScheduler()

    private val policies = Slot(SnapshotKey.Policies, policiesCompiler)
    private val apps = ConcurrentHashMap<AppId, Slot<A>>()
    private val evictionLock = Any()

    /** The last decoded revision read or installed per key, kept across eviction (see the class comment). */
    private val seen = ConcurrentHashMap<SnapshotKey, Seen>()
    private val lastLagReport = ConcurrentHashMap<SnapshotKey, Instant>()
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
     * needed document is not installed. Never does I/O, never touches an executor, and never allocates when [appId] is
     * null. Everything it does happens on the caller's thread.
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
     * The future may complete on a CoSec compile or scheduler thread. Those threads exist to compile and to keep time,
     * so callers must move their continuation elsewhere (RFC 0003 §4.3: `publishOn(boundedElastic)`) rather than
     * evaluate on them.
     *
     * @throws IllegalArgumentException when [appId] is not a valid app id; check [SnapshotKey.App.isValidAppId] first.
     */
    fun load(appId: AppId? = null): CompletableFuture<Snapshot<P, A>> {
        val appKey = appId?.let { SnapshotKey.App(it) }
        if (closed) {
            return CompletableFuture.failedFuture(IllegalStateException("SnapshotSource is closed."))
        }
        val policiesEntry = policies.forRequest() ?: error("The policies slot is never removed.")
        if (appKey == null) {
            return policiesEntry.thenApply { it.alone }
        }
        val appEntry = withAppSlot(appKey) { it.forRequest() }
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

    /**
     * Reads and compiles [key] again on this instance only, whether or not it is installed, with a full read that
     * bypasses the digest check. When a load is in flight, the returned future is that of the load started after it.
     */
    fun reload(key: SnapshotKey): CompletableFuture<SnapshotEntry<*>> = when (key) {
        SnapshotKey.Policies -> policies.reload(force = true)!!.thenApply<SnapshotEntry<*>> { it }
        is SnapshotKey.App -> withAppSlot(key) { it.reload(force = true) }.thenApply<SnapshotEntry<*>> { it }
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

    /** Reports every document lagging past [SnapshotOptions.lagWarn], at most once per [SnapshotOptions.lagLogInterval]. */
    internal fun reportLag() {
        val now = clock.instant()
        for (entry in entries()) {
            if (entry.state is DocumentState.Failed) {
                continue
            }
            val lag = entry.lagAt(now)
            val escalated = lag > options.lagEscalate
            if (!escalated && lag <= options.lagWarn) {
                lastLagReport.remove(entry.key)
                continue
            }
            val last = lastLagReport[entry.key]
            if (last != null && Duration.between(last, now) < options.lagLogInterval) {
                continue
            }
            lastLagReport[entry.key] = now
            metrics.onLagExceeded(entry.key, lag, escalated)
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
        } catch (error: Exception) {
            log.error(error) { "Snapshot revalidation pass failed." }
        }
    }

    /** Runs [task] on the scheduler, off every request thread; runs it here only if the scheduler is gone. */
    private fun offRequestThread(task: () -> Unit) {
        try {
            scheduler.execute(task)
        } catch (rejected: RejectedExecutionException) {
            log.debug { "Scheduler rejected housekeeping ($rejected); running it in place." }
            task()
        }
    }

    /**
     * Runs [action] on the slot of [key], retrying with a fresh slot when the one found was removed concurrently
     * (evicted or forgotten) before the action could start a load on it.
     */
    private inline fun <R : Any> withAppSlot(key: SnapshotKey.App, action: (Slot<A>) -> R?): R {
        while (true) {
            action(apps.computeIfAbsent(key.appId) { Slot(key, appCompiler) })?.let { return it }
        }
    }

    private fun usableOf(key: SnapshotKey): CompletableFuture<out SnapshotEntry<*>> = when (key) {
        SnapshotKey.Policies -> policies.usable()!!
        is SnapshotKey.App -> withAppSlot(key) { it.usable() }
    }

    private fun installedSlots(): List<Slot<*>> = buildList {
        if (policies.installed != null) add(policies)
        apps.values.filterTo(this) { it.installed != null }
    }

    /** Runs [reload] on [slots], [SnapshotOptions.reloadConcurrency] at a time; a removed slot counts as done. */
    private fun reloadAll(slots: List<Slot<*>>, reload: (Slot<*>) -> CompletableFuture<*>?): CompletableFuture<Void> {
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
            val reloading = reload(slots[index]) ?: CompletableFuture.completedFuture(null)
            reloading.whenComplete { _, _ ->
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
        val loads = keys.map { usableOf(it) }
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
                if (victim.removeIfIdle()) {
                    lastLagReport.remove(victim.key)
                    log.debug { "Evicted snapshot document [${victim.key}]." }
                }
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

        /**
         * The LRU stamp. A plain field: every hit writes it, and eviction only needs an approximate order, so it does
         * without the memory barrier a volatile write would cost on the hot path.
         */
        var lastAccess: Long = ticker.asLong
            private set

        // Guarded by this.
        private var inFlight: Load? = null
        private var dirty = false
        private var forceNext = false
        private var next: CompletableFuture<SnapshotEntry<T>>? = null
        private var removed = false

        /** One read of the store and what follows it. Finishes exactly once. */
        inner class Load(
            val known: SnapshotEntry<T>?,
            val force: Boolean,
            val result: CompletableFuture<SnapshotEntry<T>>,
        ) {
            val startedAt: Instant = clock.instant()

            /** Whether the read may have been answered from a local copy rather than the store of record. */
            var fromLocalCopy = false

            /** The digest the conditional read sent; null for an unconditional read or "I believe it absent". */
            var askedDigest: SnapshotDigest? = null

            private val finished = AtomicBoolean()

            fun claim(): Boolean = finished.compareAndSet(false, true)

            val isFinished: Boolean get() = finished.get()
        }

        fun touch() {
            lastAccess = ticker.asLong
        }

        fun isIdle(): Boolean = synchronized(this) { inFlight == null }

        /** Marks this slot removed and drops it from the map, unless a load is in flight. */
        fun removeIfIdle(): Boolean {
            val appKey = key as? SnapshotKey.App ?: return false
            synchronized(this) {
                if (inFlight != null || removed) {
                    return false
                }
                removed = true
            }
            apps.computeIfPresent(appKey.appId) { _, current -> current.takeUnless { it === this } }
            return true
        }

        /** The installed entry if it is usable, otherwise a load; null when this slot was removed. */
        fun usable(): CompletableFuture<SnapshotEntry<T>>? {
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
            installed?.let {
                touch()
                metrics.onLoad(key.kind, LoadOutcome.HIT)
                return CompletableFuture.completedFuture(it)
            }
            val shared = load() ?: return null
            val own = RequestView()
            val timeout = if (shared.isDone) null else scheduleTimeout(own)
            shared.whenComplete { entry, error ->
                val result = entry ?: transientFailure(clock.instant(), unwrap(error))
                val outcome = if (result.state is DocumentState.Failed) LoadOutcome.FAILED else LoadOutcome.COLD
                if (own.answer(result, outcome)) {
                    timeout?.cancel(false)
                }
            }
            return own
        }

        /**
         * One request's future. Whoever answers first (the shared load or the timeout) records the outcome before
         * completing it, so a caller that sees the result also sees the metric.
         */
        private inner class RequestView : CompletableFuture<SnapshotEntry<T>>() {
            private val answered = AtomicBoolean()

            fun answer(entry: SnapshotEntry<T>, outcome: LoadOutcome): Boolean {
                if (!answered.compareAndSet(false, true)) {
                    return false
                }
                try {
                    metrics.onLoad(key.kind, outcome)
                } finally {
                    complete(entry)
                }
                return true
            }
        }

        private fun scheduleTimeout(own: RequestView): ScheduledFuture<*>? {
            val timeout = options.coldLoadTimeout
            val onTimeout = Runnable {
                val error = SnapshotUnavailableException(key, "not loaded within $timeout", TimeoutException())
                own.answer(failedEntry(error, clock.instant()), LoadOutcome.TIMEOUT)
            }
            return try {
                scheduler.schedule(onTimeout, timeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (rejected: RejectedExecutionException) {
                own.answer(transientFailure(clock.instant(), rejected), LoadOutcome.FAILED)
                null
            }
        }

        /** Starts a load unless one is in flight, in which case it returns that one. Null when this slot was removed. */
        fun load(force: Boolean = false): CompletableFuture<SnapshotEntry<T>>? {
            val load: Load
            synchronized(this) {
                if (removed) {
                    return null
                }
                inFlight?.let { return it.result }
                load = Load(installed, force, CompletableFuture())
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

        /** Starts the load a notification or [reload] asked for while another was in flight. */
        private fun startNext(force: Boolean, result: CompletableFuture<SnapshotEntry<T>>?) {
            val load: Load
            synchronized(this) {
                if (inFlight != null || removed) {
                    // Another load started meanwhile: it postdates the change too.
                    inFlight?.result?.let { current -> result?.let { current.whenComplete { e, _ -> it.complete(e) } } }
                    return
                }
                load = Load(installed, force, result ?: CompletableFuture())
                inFlight = load
            }
            read(load)
        }

        private fun read(load: Load) {
            guarded(load) {
                val reading = readFor(load)
                withTimeout(reading).whenComplete { read, error ->
                    guarded(load) { onRead(load, read, error) }
                }
            }
        }

        /** Chooses the read: see the class comment of [SnapshotSource] for why seen keys never use a local copy. */
        private fun readFor(load: Load): CompletableFuture<SnapshotRead> {
            val known = load.known
            if (known == null && !seen.containsKey(key)) {
                load.fromLocalCopy = true
                return store.read(key)
            }
            val digest = when {
                known == null || load.force -> null
                known.origin == EntryOrigin.UNCOMPILABLE -> null
                else -> known.digest
            }
            load.askedDigest = digest
            return store.readIfChanged(key, digest)
        }

        /** [reading], failed after [SnapshotOptions.storeReadTimeout] if the store never answers. */
        private fun withTimeout(reading: CompletableFuture<SnapshotRead>): CompletableFuture<SnapshotRead> {
            if (reading.isDone) {
                return reading
            }
            val bounded = CompletableFuture<SnapshotRead>()
            val timeout = try {
                scheduler.schedule(
                    {
                        bounded.completeExceptionally(
                            TimeoutException("The store did not answer within ${options.storeReadTimeout}.")
                        )
                    },
                    options.storeReadTimeout.toNanos(),
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
                    seen.putIfAbsent(key, Seen(read.document.revision, read.digest))
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
                load.fromLocalCopy -> onTransientFailure(
                    load,
                    IllegalStateException("Unchanged answer to an unconditional read.")
                )
                load.askedDigest == null -> onAbsent(load)
                known == null -> onTransientFailure(
                    load,
                    IllegalStateException("Unchanged answer without a known digest.")
                )
                else -> {
                    known.confirm(load.startedAt)
                    finish(load, known, install = false)
                }
            }
        }

        private fun onUndecodable(load: Load, cause: Throwable) {
            seen.putIfAbsent(key, Seen(0, null))
            val known = load.known
            if (known != null && known.origin == EntryOrigin.UNDECODABLE) {
                known.confirm(load.startedAt)
                finish(load, known, install = false)
                return
            }
            val failure = SnapshotUnavailableException(key, "the stored document cannot be decoded", cause)
            val entry =
                SnapshotEntry(key, DocumentState.Failed(failure), 0, null, load.startedAt, EntryOrigin.UNDECODABLE)
            finish(load, entry, install = true)
        }

        private fun onAbsent(load: Load) {
            val known = load.known
            if (known != null && known.origin.storedAbsent) {
                known.confirm(load.startedAt)
                finish(load, known, install = false)
                return
            }
            val lastSeen = seen[key]
            if (lastSeen == null) {
                finish(
                    load,
                    SnapshotEntry(key, DocumentState.Absent, 0, null, load.startedAt, EntryOrigin.ABSENT),
                    install = true
                )
                return
            }
            val error = SnapshotUnavailableException(
                key,
                "the store has no document, but revision ${lastSeen.revision} was seen; it may have lost data",
            )
            offRequestThread {
                log.error(
                    error
                ) { "Snapshot document [$key] disappeared from the store; failing closed." }
            }
            metrics.onDocumentLost(key, lastSeen.revision)
            finish(
                load,
                SnapshotEntry(key, DocumentState.Failed(error), 0, null, load.startedAt, EntryOrigin.LOST),
                install = true
            )
        }

        private fun compileOffThread(load: Load, read: SnapshotRead.Present) {
            try {
                compileExecutor.execute {
                    guarded(load) { compileAndInstall(load, read) }
                }
            } catch (rejected: RejectedExecutionException) {
                onTransientFailure(load, SnapshotUnavailableException(key, "the compile queue is full", rejected))
                if (load.known != null) {
                    retryLater()
                }
            }
        }

        /** A reload that found the compile queue full tries again shortly instead of waiting for the next pass. */
        private fun retryLater() {
            try {
                scheduler.schedule(
                    { reload(force = false) },
                    options.rejectedReloadRetry.toNanos(),
                    TimeUnit.NANOSECONDS
                )
            } catch (rejected: RejectedExecutionException) {
                log.debug { "No reload retry: $rejected" }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun compileAndInstall(load: Load, read: SnapshotRead.Present) {
            val document = read.document
            val begin = System.nanoTime()
            val state: DocumentState<T> = try {
                DocumentState.Present(compiler.compile(key, document))
            } catch (error: VirtualMachineError) {
                throw error
            } catch (error: Throwable) {
                DocumentState.Failed(
                    SnapshotUnavailableException(key, "revision ${document.revision} cannot be compiled", error)
                )
            }
            metrics.onCompile(key.kind, Duration.ofNanos(System.nanoTime() - begin), state is DocumentState.Present)
            val origin = if (state is DocumentState.Present) EntryOrigin.DOCUMENT else EntryOrigin.UNCOMPILABLE
            finish(
                load,
                SnapshotEntry(key, state, document.revision, read.digest, load.startedAt, origin),
                install = true
            )
        }

        /** The store could not be read: keep what is installed (F6), or answer failed without installing (F1). */
        private fun onTransientFailure(load: Load, cause: Throwable) {
            val known = load.known
            if (known != null) {
                offRequestThread {
                    log.warn(
                        cause
                    ) { "Reloading snapshot document [$key] failed; keeping revision ${known.revision}." }
                }
                finish(load, known, install = false)
                return
            }
            offRequestThread { log.warn(cause) { "Loading snapshot document [$key] failed." } }
            finish(load, transientFailure(load.startedAt, cause), install = false)
        }

        /**
         * Ends [load] once: records the install (metrics first, then visible to [peek]), clears the in-flight load, starts
         * the next load if a change arrived meanwhile, and only then completes the load's future. Eviction and dropping an
         * empty slot run afterwards on the scheduler, off the request thread.
         */
        private fun finish(load: Load, entry: SnapshotEntry<T>, install: Boolean) {
            if (!load.claim()) {
                log.error { "Snapshot load of [$key] tried to finish twice; ignored." }
                return
            }
            if (install) {
                recordInstall(entry)
            }
            val again: Boolean
            val force: Boolean
            val nextResult: CompletableFuture<SnapshotEntry<T>>?
            val empty: Boolean
            synchronized(this) {
                if (install) {
                    installed = entry
                }
                if (inFlight === load) {
                    inFlight = null
                }
                val wanted = dirty || (install && load.fromLocalCopy)
                again = wanted && !closed && !removed
                force = forceNext
                nextResult = next
                dirty = false
                forceNext = false
                next = null
                empty = installed == null
            }
            if (again) {
                startNext(force, nextResult)
            } else {
                nextResult?.complete(entry)
            }
            if (empty || install) {
                offRequestThread { if (empty) forgetIfEmpty() else evictApps() }
            }
            load.result.complete(entry)
        }

        private fun recordInstall(entry: SnapshotEntry<T>) {
            val regressedFrom = synchronized(this) { recordSeen(entry) }
            metrics.onInstall(entry)
            log.debug { "Installed $entry." }
            if (regressedFrom != null) {
                offRequestThread {
                    log.error {
                        "Snapshot document [$key] regressed from revision ${regressedFrom.revision} to ${entry.revision}; " +
                            "the store may have lost a write (for example a Redis failover). Installing what the store holds."
                    }
                }
                metrics.onRegression(key, regressedFrom.revision, entry.revision)
            }
        }

        /** Drops an app slot that holds nothing, so failed cold loads of unknown apps leave no trace. */
        private fun forgetIfEmpty() {
            if (installed == null) {
                removeIfIdle()
            }
        }

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
            reloadAll(installedSlots()) { it.reload(force = false) }
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

        fun Throwable.isFormatFailure(): Boolean = generateSequence(
            this
        ) { it.cause }.take(16).any { it is SnapshotFormatException }

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
