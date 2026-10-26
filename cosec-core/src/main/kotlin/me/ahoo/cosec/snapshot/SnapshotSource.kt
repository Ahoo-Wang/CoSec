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
import java.util.concurrent.LinkedBlockingQueue
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
 * Holds the compiled policy snapshot (RFC 0003 §4.2–§4.5, RFC 0004 §7.4): one shared document, which every request needs
 * and which is never evicted, and the documents of each [SnapshotFamily], each family holding up to its own bound and
 * evicting least recently used. RFC 0003's source shares [SnapshotKey.Policies] and has one family of
 * [SnapshotKey.App] documents; the 8.0 source ([documents]) shares [SnapshotKey.Platform] and has the app and tenant
 * families. Every guarantee below holds for every key of every family alike.
 *
 * - [peek] is the hot path: a synchronous in-memory lookup with no future and no I/O, safe on an event loop.
 * - [load] reads and compiles what is missing. The store is read on the caller's thread (stores never block), every
 *   compilation runs on a bounded compile pool, and concurrent loads of one key share a single read and compilation.
 * - Installs of one key happen in the order their loads started: at most one load per key is in flight, each load
 *   finishes exactly once, and a change notification that arrives during a load starts another as that one finishes,
 *   under the same lock, so nothing can slip in between.
 * - Installed documents are revalidated every [SnapshotOptions.revalidateInterval] with a conditional read of the store
 *   of record, so a document's lag (time since the store last confirmed it) is bounded while the store is reachable.
 *
 * Only a key never seen by this source is cold-loaded with [SnapshotDocumentStore.read], which may answer from a local
 * copy. That install counts as confirmed [SnapshotDocumentStore.localCopyMaxAge] before the read, the oldest the copy can
 * be, and is followed at once by a conditional read that confirms or replaces it. A key seen before (for example one
 * evicted since) is cold-loaded with the conditional read, so an older local copy can never replace what this instance
 * already served.
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
 * [SnapshotMetrics] callbacks that throw are logged and ignored; a [VirtualMachineError] is rethrown, but only after the
 * load it interrupted has finished, so no key is ever left waiting for it. Compilation itself has no time bound: a stuck
 * [SnapshotCompiler] holds one compile thread, and the requests waiting for it are bounded by
 * [SnapshotOptions.coldLoadTimeout].
 *
 * Threads: compilation runs on the compile pool; the scheduler thread only keeps time (timeouts, periodic passes,
 * retries); eviction and log lines with stack traces run on a separate housekeeping thread, so a slow log appender
 * cannot delay a timeout.
 */
class SnapshotSource<P : Any, A : Any> internal constructor(
    private val store: SnapshotDocumentStore,
    /** The document every request needs. */
    private val sharedKey: SnapshotKey,
    sharedCompiler: SnapshotCompiler<P>,
    families: List<SnapshotFamily<A>>,
    private val options: SnapshotOptions,
    private val clock: Clock,
    metrics: SnapshotMetrics,
    compileExecutor: Executor?,
    scheduler: ScheduledExecutorService?,
    private val ticker: LongSupplier,
) : AutoCloseable {

    /** RFC 0003's source: the policies document, and app documents. */
    constructor(
        store: SnapshotDocumentStore,
        policiesCompiler: SnapshotCompiler<P>,
        appCompiler: SnapshotCompiler<A>,
        options: SnapshotOptions = SnapshotOptions(),
        clock: Clock = Clock.systemUTC(),
        metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
    ) : this(store, policiesCompiler, appCompiler, options, clock, metrics, null, null, System::nanoTime)

    internal constructor(
        store: SnapshotDocumentStore,
        policiesCompiler: SnapshotCompiler<P>,
        appCompiler: SnapshotCompiler<A>,
        options: SnapshotOptions,
        clock: Clock,
        metrics: SnapshotMetrics,
        compileExecutor: Executor?,
        scheduler: ScheduledExecutorService?,
        ticker: LongSupplier,
    ) : this(
        store,
        SnapshotKey.Policies,
        policiesCompiler,
        listOf(SnapshotFamily(SnapshotKind.APP, appCompiler, options.maxApps)),
        options,
        clock,
        metrics,
        compileExecutor,
        scheduler,
        ticker,
    )

    private val metrics: SnapshotMetrics = GuardedSnapshotMetrics(metrics)
    private val ownsCompileExecutor = compileExecutor == null
    private val compileExecutor: Executor = compileExecutor ?: newCompileExecutor(options)
    private val ownsScheduler = scheduler == null
    private val scheduler: ScheduledExecutorService = scheduler ?: newScheduler()
    private val housekeeping: ThreadPoolExecutor = newHousekeeping()

    private val shared = Slot(sharedKey, sharedCompiler)
    private val families: List<Family> = families.map { Family(it) }
    private val evictionLock = Any()

    init {
        require(
            this.families.map { it.spec.kind }.toSet().size == this.families.size
        ) { "Families must differ in kind." }
        require(this.families.none { it.spec.kind == sharedKey.kind }) { "The shared key cannot be a family's." }
    }

    /** The last decoded revision read or installed per key, kept across eviction (see the class comment). */
    private val seen = ConcurrentHashMap<SnapshotKey, Seen>()
    private val lastLagReport = ConcurrentHashMap<SnapshotKey, Instant>()
    private val pendingPrewarms: MutableSet<CompletableFuture<Void>> = ConcurrentHashMap.newKeySet()
    private val pendingRequests: MutableSet<Slot<*>.RequestView> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var closed = false

    @Volatile
    private var prewarmed = false

    /** Tests only: runs between finding a family's slot and using it, to force an eviction into that gap. */
    @Volatile
    internal var afterSlotLookup: ((SnapshotKey) -> Unit)? = null

    private val subscription: AutoCloseable = store.subscribe(ChangeListener())
    private val periodic: ScheduledFuture<*>? = schedulePeriodic()

    /** Whether a [prewarm] has completed: until then the instance should not report ready (RFC 0003 §4.3). */
    val isPrewarmed: Boolean get() = prewarmed

    /**
     * The installed snapshot for a request that needs the app document of [appId] (or none when null), or null when a
     * needed document is not installed or this source is closed. Never does I/O, never touches an executor, and never
     * allocates when [appId] is null. Everything it does happens on the caller's thread.
     *
     * A document that is installed but failed is returned as such: the caller fails the layers it feeds.
     */
    fun peek(appId: AppId? = null): Snapshot<P, A>? {
        if (closed) {
            return null
        }
        val policiesEntry = shared.installed ?: return null
        if (appId == null) {
            metrics.onLoad(sharedKey.kind, LoadOutcome.HIT)
            return policiesEntry.alone
        }
        val appEntry = peekScoped(family(SnapshotKind.APP), appId) ?: return null
        metrics.onLoad(sharedKey.kind, LoadOutcome.HIT)
        metrics.onLoad(SnapshotKind.APP, LoadOutcome.HIT)
        return Snapshot(policiesEntry, appEntry)
    }

    /**
     * The installed shared document and the documents of [keys], each a key of one of this source's families, or null
     * when one of them is not installed or this source is closed. Like [peek]: no I/O, no executor, the caller's thread.
     *
     * @throws IllegalArgumentException when a key belongs to no family of this source.
     */
    fun peekAll(keys: List<SnapshotKey>): SnapshotSet<P, A>? {
        val families = keys.map(::familyOf)
        if (closed) {
            return null
        }
        val sharedEntry = shared.installed ?: return null
        val entries = ArrayList<SnapshotEntry<A>>(keys.size)
        for (i in keys.indices) {
            entries += peekScoped(families[i], families[i].spec.idOf(keys[i])) ?: return null
        }
        metrics.onLoad(sharedKey.kind, LoadOutcome.HIT)
        families.forEach { metrics.onLoad(it.spec.kind, LoadOutcome.HIT) }
        return SnapshotSet(sharedEntry, entries)
    }

    private fun peekScoped(family: Family, id: String): SnapshotEntry<A>? {
        val slot = family.slots[id] ?: return null
        val entry = slot.installed ?: return null
        if (slot.removed) {
            return null
        }
        slot.touch()
        return entry
    }

    /**
     * The snapshot for a request that needs the app document of [appId] (or none when null), loading what is missing.
     * Never blocks the caller. Each caller gets its own future, so cancelling it leaves the shared load running.
     *
     * The future completes normally even when a document cannot be loaded: that document is [DocumentState.Failed],
     * including when it is not ready within [SnapshotOptions.coldLoadTimeout] (the load goes on in the background).
     * It completes exceptionally only when this source is closed, including while the request waits.
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
            return CompletableFuture.failedFuture(closedError())
        }
        val policiesEntry = shared.forRequest() ?: error("The shared slot is never removed.")
        if (appKey == null) {
            return policiesEntry.thenApply { it.alone }
        }
        val appEntry = withSlot(appKey) { it.forRequest() }
        return policiesEntry.thenCombine(appEntry) { p, a -> Snapshot(p, a) }
    }

    /**
     * The shared document and the documents of [keys], loading what is missing; see [load] for how it completes.
     *
     * @throws IllegalArgumentException when a key belongs to no family of this source.
     */
    fun loadAll(keys: List<SnapshotKey>): CompletableFuture<SnapshotSet<P, A>> {
        keys.forEach(::familyOf)
        if (closed) {
            return CompletableFuture.failedFuture(closedError())
        }
        val sharedEntry = shared.forRequest() ?: error("The shared slot is never removed.")
        val entries = keys.map { key -> withSlot(key) { it.forRequest() } }
        return CompletableFuture.allOf(sharedEntry, *entries.toTypedArray())
            .thenApply { SnapshotSet(sharedEntry.join(), entries.map { it.join() }) }
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
        checkFresh(tenantId) { snapshot.lagAt(it) }
    }

    /** [checkFresh] for the documents of one evaluation of the 8.0 source: the oldest of them decides. */
    fun checkFresh(snapshot: SnapshotSet<*, *>, tenantId: String) {
        checkFresh(tenantId) { snapshot.lagAt(it) }
    }

    private inline fun checkFresh(tenantId: String, lagAt: (Instant) -> Duration) {
        if (options.failClosedTenants.isEmpty() || tenantId !in options.failClosedTenants) {
            return
        }
        val lag = lagAt(clock.instant())
        if (lag > options.failClosedMaxLag) {
            throw SnapshotStaleException(tenantId, lag, options.failClosedMaxLag)
        }
    }

    /** Every installed entry: for gauges and the actuator endpoint. */
    fun entries(): List<SnapshotEntry<*>> = buildList {
        shared.installed?.let { add(it) }
        families.forEach { family -> family.slots.values.forEach { slot -> slot.installed?.let { add(it) } } }
    }

    /**
     * Reads and compiles [key] again on this instance only, whether or not it is installed, with a full read that
     * bypasses the digest check. When a load is in flight, the returned future is that of the load started after it.
     */
    fun reload(key: SnapshotKey): CompletableFuture<SnapshotEntry<*>> =
        if (key == sharedKey) {
            shared.reload(force = true)!!.thenApply<SnapshotEntry<*>> { it }
        } else {
            withSlot(key) { it.reload(force = true) }.thenApply<SnapshotEntry<*>> { it }
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
        pendingPrewarms.forEach { it.completeExceptionally(closedError()) }
        pendingRequests.forEach { it.abort(closedError()) }
        housekeeping.shutdown()
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
            if (!lagReportDue(entry.key, now)) {
                continue
            }
            metrics.onLagExceeded(entry.key, lag, escalated)
            if (escalated) {
                log.error { "Snapshot document [${entry.key}] revision ${entry.revision} is stale: not confirmed for $lag." }
            } else {
                log.warn { "Snapshot document [${entry.key}] revision ${entry.revision} has not been confirmed for $lag." }
            }
        }
    }

    private fun lagReportDue(key: SnapshotKey, now: Instant): Boolean {
        val last = lastLagReport[key]
        if (last != null && Duration.between(last, now) < options.lagLogInterval) {
            return false
        }
        lastLagReport[key] = now
        return true
    }

    private fun closedError() = IllegalStateException("SnapshotSource is closed.")

    private fun schedulePeriodic(): ScheduledFuture<*>? {
        if (options.revalidateInterval.isZero) {
            return null
        }
        val millis = options.revalidateInterval.toMillis()
        return scheduler.scheduleWithFixedDelay(::periodicTick, millis, millis, TimeUnit.MILLISECONDS)
    }

    /** Never lets anything escape: a throwing task would silently cancel the periodic schedule. */
    @Suppress("TooGenericExceptionCaught")
    private fun periodicTick() {
        try {
            tick()
        } catch (error: Throwable) {
            log.error(error) { "Snapshot revalidation pass failed." }
        }
    }

    /** Runs [task] on the housekeeping thread, off every request thread and off the timer; in place if it is gone. */
    private fun offRequestThread(task: () -> Unit) {
        try {
            housekeeping.execute(task)
        } catch (rejected: RejectedExecutionException) {
            log.debug { "Housekeeping rejected a task ($rejected); running it in place." }
            task()
        }
    }

    /**
     * Runs [action] on the slot of [key], retrying with a fresh slot when the one found was removed concurrently
     * (evicted or forgotten) before the action could start a load on it. A removed slot still in the map is dropped
     * here too, so the retry never waits for the remover.
     */
    private inline fun <R : Any> withSlot(key: SnapshotKey, action: (Slot<A>) -> R?): R {
        val family = familyOf(key)
        val id = family.spec.idOf(key)
        while (true) {
            val slot = family.slots.computeIfAbsent(id) { Slot(key, family.spec.compiler) }
            afterSlotLookup?.invoke(key)
            action(slot)?.let { return it }
            family.slots.remove(id, slot)
        }
    }

    private fun usableOf(key: SnapshotKey): CompletableFuture<out SnapshotEntry<*>> =
        if (key == sharedKey) shared.usable()!! else withSlot(key) { it.usable() }

    /** The family [key] belongs to; [IllegalArgumentException] when it is no document of this source. */
    private fun familyOf(key: SnapshotKey): Family =
        families.firstOrNull { it.spec.kind == key.kind }
            ?: throw IllegalArgumentException("[$key] is not one of this source's documents.")

    private fun family(kind: SnapshotKind): Family =
        families.firstOrNull { it.spec.kind == kind }
            ?: throw IllegalArgumentException("This source has no $kind documents.")

    private fun installedSlots(): List<Slot<*>> = buildList {
        if (shared.installed != null) add(shared)
        families.forEach { family -> family.slots.values.filterTo(this) { it.installed != null } }
    }

    /**
     * Runs [reload] on [slots], [SnapshotOptions.reloadConcurrency] at a time; a removed slot counts as done. Each lane
     * loops while reloads complete inline (a synchronous store), so the stack never grows with the number of slots.
     */
    private fun reloadAll(slots: List<Slot<*>>, reload: (Slot<*>) -> CompletableFuture<*>?): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        if (slots.isEmpty()) {
            done.complete(null)
            return done
        }
        val next = AtomicInteger()
        val remaining = AtomicInteger(slots.size)
        fun finishedOne() {
            if (remaining.decrementAndGet() == 0) {
                done.complete(null)
            }
        }
        fun lane() {
            while (true) {
                val index = next.getAndIncrement()
                if (index >= slots.size) {
                    return
                }
                val reloading = reload(slots[index])
                if (reloading == null || reloading.isDone) {
                    finishedOne()
                    continue
                }
                reloading.whenComplete { _, _ ->
                    finishedOne()
                    lane()
                }
                return
            }
        }
        repeat(minOf(options.reloadConcurrency, slots.size)) { lane() }
        return done
    }

    private fun attemptPrewarm(keys: List<SnapshotKey>, backoff: Duration, done: CompletableFuture<Void>) {
        if (closed) {
            done.completeExceptionally(closedError())
            return
        }
        val loads = keys.map { usableOf(it) }
        CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, _ ->
            val answered = loads.filterNot { it.isCompletedExceptionally }.map { it.join() }
            val failed = answered.filter { it.state is DocumentState.Failed }
            if (failed.isEmpty() && answered.size == loads.size) {
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
                    TimeUnit.MILLISECONDS,
                )
            } catch (rejected: RejectedExecutionException) {
                done.completeExceptionally(rejected)
            }
        }
    }

    /** Tests only: evicts the slot of [key] now, as [evictBeyond] would, if it is idle. */
    internal fun evict(key: SnapshotKey): Boolean {
        val family = familyOf(key)
        return family.slots[family.spec.idOf(key)]?.removeIfIdle(onlyIfEmpty = false) ?: false
    }

    /** Evicts the least recently used idle documents of [family] beyond its bound. */
    private fun evictBeyond(family: Family) {
        val slots = family.slots
        val bound = family.spec.maxEntries
        if (slots.size <= bound) {
            return
        }
        synchronized(evictionLock) {
            while (slots.size > bound) {
                val victim = slots.values.filter { it.installed != null && it.isIdle() }.minByOrNull { it.lastAccess } ?: return
                if (victim.removeIfIdle(onlyIfEmpty = false)) {
                    lastLagReport.remove(victim.key)
                    log.debug { "Evicted snapshot document [${victim.key}]." }
                }
            }
        }
    }

    /** One family's documents, by id. */
    private inner class Family(val spec: SnapshotFamily<A>) {
        val slots = ConcurrentHashMap<String, Slot<A>>()
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
        private var retryDelay: Duration = options.rejectedReloadRetry

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

            /**
             * When what this load read is known to have been current: the start of the read, or for a local copy the
             * oldest that copy can be, so that only a conditional read can make it look fresh.
             */
            val confirmedAt: Instant get() = if (fromLocalCopy) startedAt.minus(store.localCopyMaxAge) else startedAt

            fun claim(): Boolean = finished.compareAndSet(false, true)

            val isFinished: Boolean get() = finished.get()
        }

        fun touch() {
            lastAccess = ticker.asLong
        }

        fun isIdle(): Boolean = synchronized(this) { inFlight == null }

        /**
         * Marks this slot removed and drops it from the map, unless a load is in flight or, with [onlyIfEmpty], a
         * document is installed. The checks and the mark happen under this slot's lock.
         */
        fun removeIfIdle(onlyIfEmpty: Boolean): Boolean {
            // The shared slot is never removed.
            val family = families.firstOrNull { it.spec.kind == key.kind } ?: return false
            synchronized(this) {
                val holdsDocument = onlyIfEmpty && installed != null
                if (inFlight != null || removed || holdsDocument) {
                    return false
                }
                removed = true
                family.slots.computeIfPresent(
                    family.spec.idOf(key)
                ) { _, current -> current.takeUnless { it === this } }
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
                metrics.onLoad(key.kind, LoadOutcome.HIT)
                return CompletableFuture.completedFuture(it)
            }
            val shared = load() ?: return null
            val own = RequestView()
            pendingRequests.add(own)
            if (closed) {
                own.abort(closedError())
                return own
            }
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
         * One request's future. Whoever answers first (the shared load, the timeout, or [close]) claims it; an answer
         * records the outcome before completing, so a caller that sees the result also sees the metric.
         */
        inner class RequestView : CompletableFuture<SnapshotEntry<T>>() {
            private val answered = AtomicBoolean()

            fun answer(entry: SnapshotEntry<T>, outcome: LoadOutcome): Boolean {
                if (!answered.compareAndSet(false, true)) {
                    return false
                }
                pendingRequests.remove(this)
                try {
                    metrics.onLoad(key.kind, outcome)
                } finally {
                    complete(entry)
                }
                return true
            }

            fun abort(error: Throwable) {
                if (answered.compareAndSet(false, true)) {
                    pendingRequests.remove(this)
                    completeExceptionally(error)
                }
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

        /**
         * Starts a load unless one is in flight, in which case it returns that one. Null when this slot was removed;
         * after [close], an already failed entry.
         */
        fun load(force: Boolean = false): CompletableFuture<SnapshotEntry<T>>? {
            val load: Load
            synchronized(this) {
                if (removed) {
                    return null
                }
                if (closed) {
                    return CompletableFuture.completedFuture(failedEntry(closedError(), clock.instant()))
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
            seen.putIfAbsent(key, Seen(0, null))
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
            val lastSeen = seen[key]
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
            offRequestThread {
                log.error(error) { "Snapshot document [$key] disappeared from the store; failing closed." }
            }
            metrics.onDocumentLost(key, lastSeen.revision)
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

        /**
         * A reload that found the compile queue full tries again, with a delay that doubles while the queue stays full,
         * up to the revalidation interval (or [SnapshotOptions.prewarmMaxBackoff] when periodic revalidation is off).
         */
        private fun retryLater() {
            val delay = retryDelay
            val cap = options.revalidateInterval.takeUnless { it.isZero } ?: options.prewarmMaxBackoff
            retryDelay = minOf(delay.multipliedBy(2), cap)
            try {
                scheduler.schedule({ reload(force = false) }, delay.toNanos(), TimeUnit.NANOSECONDS)
            } catch (rejected: RejectedExecutionException) {
                log.debug { "No reload retry: $rejected" }
            }
        }

        private fun compileAndInstall(load: Load, read: SnapshotRead.Present) {
            val document = read.document
            val begin = System.nanoTime()
            val state = compileState(document)
            retryDelay = options.rejectedReloadRetry
            metrics.onCompile(key.kind, Duration.ofNanos(System.nanoTime() - begin), state is DocumentState.Present)
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
            val failure = SnapshotUnavailableException(key, "revision ${document.revision} cannot be compiled", error)
            DocumentState.Failed(failure)
        }

        /** The store could not be read: keep what is installed (F6), or answer failed without installing (F1). */
        private fun onTransientFailure(load: Load, cause: Throwable) {
            val known = load.known
            if (known != null) {
                offRequestThread {
                    log.warn(cause) { "Reloading snapshot document [$key] failed; keeping revision ${known.revision}." }
                }
                finish(load, known, install = false)
                return
            }
            offRequestThread { log.warn(cause) { "Loading snapshot document [$key] failed." } }
            finish(load, transientFailure(load.startedAt, cause), install = false)
        }

        /**
         * Ends [load] once. The install is recorded (metrics first, then visible to [peek]); then, whatever the metrics
         * did, the state changes: the in-flight load is cleared and, if a change arrived meanwhile, the next load is
         * created under the same lock, so no other load and no removal can come between them. The load's future
         * completes last. Eviction and dropping an empty slot run afterwards on the housekeeping thread.
         */
        private fun finish(load: Load, entry: SnapshotEntry<T>, install: Boolean) {
            if (!load.claim()) {
                log.error { "Snapshot load of [$key] tried to finish twice; ignored." }
                return
            }
            try {
                if (install) {
                    recordInstall(entry)
                }
            } finally {
                advance(load, entry, install)
            }
        }

        private fun advance(load: Load, entry: SnapshotEntry<T>, install: Boolean) {
            val nextLoad: Load?
            val unanswered: CompletableFuture<SnapshotEntry<T>>?
            val empty: Boolean
            synchronized(this) {
                if (install) {
                    installed = entry
                }
                if (inFlight === load) {
                    inFlight = null
                }
                val wanted = dirty || (install && load.fromLocalCopy)
                val pending = next
                val open = !closed && !removed
                nextLoad = if (wanted && inFlight == null && open) {
                    Load(installed, forceNext, pending ?: CompletableFuture()).also { inFlight = it }
                } else {
                    null
                }
                unanswered = if (nextLoad == null) pending else null
                dirty = false
                forceNext = false
                next = null
                empty = installed == null
            }
            try {
                startOrAnswer(nextLoad, unanswered, entry)
            } finally {
                housekeep(empty, install)
                load.result.complete(entry)
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
                offRequestThread { removeIfIdle(onlyIfEmpty = true) }
            } else if (install) {
                families.firstOrNull { it.spec.kind == key.kind }?.let { family ->
                    offRequestThread {
                        evictBeyond(
                            family
                        )
                    }
                }
            }
        }

        private fun recordInstall(entry: SnapshotEntry<T>) {
            val regressedFrom = synchronized(this) { recordSeen(entry) }
            log.debug { "Installed $entry." }
            if (regressedFrom != null) {
                offRequestThread {
                    log.error {
                        "Snapshot document [$key] regressed from revision ${regressedFrom.revision} to ${entry.revision}; " +
                            "the store may have lost a write (for example a Redis failover). Installing what the store holds."
                    }
                }
            }
            try {
                metrics.onInstall(entry)
            } finally {
                if (regressedFrom != null) {
                    metrics.onRegression(key, regressedFrom.revision, entry.revision)
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

    private inner class ChangeListener : SnapshotChangeListener {
        override fun onChanged(keys: Collection<SnapshotKey>) {
            for (key in keys) {
                if (key == sharedKey) {
                    shared.onChanged()
                    continue
                }
                // A key of no family here is another source's document.
                val family = families.firstOrNull { it.spec.kind == key.kind } ?: continue
                family.slots[family.spec.idOf(key)]?.onChanged()
            }
        }

        override fun onReset() {
            log.info { "Snapshot change notifications may have been lost; reloading every installed document." }
            reloadAll(installedSlots()) { it.reload(force = false) }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}

        /** Queued housekeeping beyond this runs in place rather than piling up without bound. */
        const val HOUSEKEEPING_QUEUE = 4096

        fun unwrap(error: Throwable?): Throwable {
            var current = error ?: return IllegalStateException("The load completed without a result.")
            while (current is CompletionException || current is ExecutionException) {
                current = current.cause ?: return current
            }
            return current
        }

        fun Throwable.isFormatFailure(): Boolean =
            generateSequence(this) { it.cause }.take(16).any { it is SnapshotFormatException }

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

        fun newHousekeeping(): ThreadPoolExecutor = ThreadPoolExecutor(
            1,
            1,
            1,
            TimeUnit.MINUTES,
            LinkedBlockingQueue(HOUSEKEEPING_QUEUE),
            daemonThreads("cosec-snapshot-housekeeping"),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

        fun daemonThreads(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
