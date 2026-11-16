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
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Family
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotStaleException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier

/**
 * Holds the compiled policy snapshot (RFC 0003 §4.2–§4.5, RFC 0004 §7.4): one shared document, which every request needs
 * and which is never evicted, and the documents of each [SnapshotFamily], each family holding up to its own bound and
 * evicting least recently used. Entries for documents the store does not hold have a bound of their own and evict only
 * each other, so ids a client chooses never push out a document that exists (`SNP-7`). The 8.0 source ([DocumentSnapshots.source]) shares [SnapshotKey.Platform] and has the
 * app and tenant families. Every guarantee below holds for every key of every family alike.
 *
 * - [peek] is the hot path: a synchronous in-memory lookup with no future and no I/O, safe on an event loop.
 * - [load] reads and compiles what is missing. The store is read on the caller's thread (stores never block), every
 *   compilation runs on a bounded compile pool, and concurrent loads of one key share a single read and compilation.
 * - Installs of one key happen in the order their loads started: at most one load per key is in flight, each load
 *   finishes exactly once, and a change notification that arrives during a load starts another as that one finishes,
 *   under the same lock, so nothing can slip in between.
 * - Installed documents are revalidated by periodic passes, each a conditional read of the store of record per
 *   document, so a document's lag (time since the store last confirmed it) is bounded while the store is reachable.
 *   The passes always run: they are the only bound on a change whose invalidation was lost, or whose reload failed,
 *   which the first pass to reach its document after the change is readable installs (`SNP-6`, RC1-50). One pass runs
 *   at a time (#1215): a pass starts every [SnapshotOptions.revalidateInterval] or, when the previous one is still in
 *   flight, as soon as it ends, so a slow store never sees more than [SnapshotOptions.reloadConcurrency] of their
 *   reads. A pass that has finished no document for [SnapshotOptions.storeReadTimeout] and has no read in flight is
 *   held only by compilations: the next tick abandons it.
 *
 * Only a key never seen by this source is cold-loaded with [SnapshotDocumentStore.read], which may answer from a local
 * copy. That install counts as confirmed [SnapshotDocumentStore.localCopyMaxAge] before the read, the oldest the copy can
 * be, and is followed at once by a conditional read that confirms or replaces it. A key seen before (for example one
 * evicted since) is cold-loaded with the conditional read, so an older local copy can never replace what this instance
 * already served.
 *
 * Failures are handled as follows (RFC 0003 §4.4): a read that fails while a document is installed keeps it (F6, bounded
 * by lag and [checkFresh]); a document that is present but cannot be decoded or compiled is installed as
 * [DocumentState.Failed] (F3, F5), a compilation that ends in a [VirtualMachineError] included (`SNP-8`); a cold read
 * that fails is answered as failed and not installed (F1).
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
 * retries); eviction, lag reports and log lines with stack traces run on a separate housekeeping thread, so a slow log
 * appender cannot delay a timeout (#1252).
 */
// B2a: the snapshot source splits into its state, its transitions and its runner
@Suppress("TooManyFunctions")
@InternalCoSecApi
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

    private val metrics: SnapshotMetrics = GuardedSnapshotMetrics(metrics)

    /** How long [close] waits for this source's threads; shorter only in tests of the timeout. */
    internal var closeTimeout: Duration = CLOSE_TIMEOUT

    /**
     * Monotonic nanoseconds, the time the scheduler's timeouts keep: whether a periodic pass stalled is measured in it,
     * so a step of the wall clock does not look like time passing. Replaced only in tests.
     */
    internal var nanoTime: LongSupplier
        get() = context.nanoTime.get()
        set(value) = context.nanoTime.set(value)

    private val threads = SnapshotThreads(options, compileExecutor, scheduler)
    private val lagReports = LagReports(options, clock, this.metrics, threads, ::entries)
    private val families = SnapshotFamilies(families, sharedKey.kind, lagReports)
    private val context = SlotContext(store, sharedKey, options, clock, ticker, this.metrics, threads, this.families)
    private val shared = Slot(sharedKey, sharedCompiler, context)

    private val pendingPrewarms: MutableSet<CompletableFuture<Void>> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var prewarmed = false

    /** Tests only: runs between finding a family's slot and using it, to force an eviction into that gap. */
    @Volatile
    internal var afterSlotLookup: ((SnapshotKey) -> Unit)? = null

    // #1383: a step that throws releases what the constructor took before it, so a source that fails to build holds nothing.
    private val subscription: AutoCloseable = undoing({}) { store.subscribe(ChangeListener()) }
    private val passes = PeriodicPasses()
    private val periodic: ScheduledFuture<*> = undoing({ subscription.close() }) { schedulePeriodic() }

    /** Whether a [prewarm] has completed: until then the instance should not report ready (RFC 0003 §4.3). */
    val isPrewarmed: Boolean get() = prewarmed

    /**
     * The installed snapshot for a request that needs the app document of [appId] (or none when null), or null when a
     * needed document is not installed or this source is closed. Never does I/O, never touches an executor, and never
     * allocates when [appId] is null. Everything it does happens on the caller's thread.
     *
     * A document that is installed but failed is returned as such: the caller fails every evaluation that reads it.
     */
    fun peek(appId: String? = null): Snapshot<P, A>? {
        if (context.closed.get()) {
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
    internal fun peekAll(keys: List<SnapshotKey>): SnapshotSet<P, A>? {
        val families = keys.map(::familyOf)
        if (context.closed.get()) {
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

    private fun peekScoped(family: Family<A>, id: String): SnapshotEntry<A>? {
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
    fun load(appId: String? = null): CompletableFuture<Snapshot<P, A>> {
        val appKey = appId?.let { SnapshotKey.App(it) }
        if (context.closed.get()) {
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
    @InternalCoSecApi
    fun loadAll(keys: List<SnapshotKey>): CompletableFuture<SnapshotSet<P, A>> {
        keys.forEach(::familyOf)
        if (context.closed.get()) {
            return CompletableFuture.failedFuture(closedError())
        }
        val sharedEntry = shared.forRequest() ?: error("The shared slot is never removed.")
        val entries = keys.map { key -> withSlot(key) { it.forRequest() } }
        return CompletableFuture.allOf(sharedEntry, *entries.toTypedArray())
            .thenApply { SnapshotSet(sharedEntry.join(), entries.map { it.join() }) }
    }

    /**
     * Completes once the shared document and then each of [keys] has been read: installed, or failed. The shared document
     * comes first, so that no app or tenant document this starts compiles against default limits. Unlike [loadAll] it
     * does not give up at [SnapshotOptions.coldLoadTimeout], and it reads again only a document that is missing or
     * failed. Each call gets its own future, so cancelling it leaves the loads running. It completes exceptionally only
     * when this source is closed.
     *
     * @throws IllegalArgumentException when a key belongs to no family of this source.
     */
    @InternalCoSecApi
    @Suppress("TooGenericExceptionCaught")
    fun settled(keys: Collection<SnapshotKey>): CompletableFuture<Void> {
        keys.forEach(::familyOf)
        val settled = CompletableFuture<Void>()
        val shared = if (context.closed.get()) CompletableFuture.completedFuture(null) else usableOf(sharedKey)
        shared.whenComplete { _, _ ->
            // What this callback throws would go to a stage nobody holds, and the wait would never end.
            try {
                val loads = if (context.closed.get()) emptyList() else keys.map(::usableOf)
                CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, _ ->
                    if (context.closed.get()) settled.completeExceptionally(closedError()) else settled.complete(null)
                }
            } catch (failure: Throwable) {
                settled.completeExceptionally(failure)
            }
        }
        return settled
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
    internal fun checkFresh(snapshot: SnapshotSet<*, *>, tenantId: String) {
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
     * If [close] ends the load first, the future answers what is installed, as for a read that failed, or a failed entry.
     */
    fun reload(key: SnapshotKey): CompletableFuture<SnapshotEntry<*>> =
        if (key == sharedKey) {
            shared.reload(force = true)!!.thenApply<SnapshotEntry<*>> { it }
        } else {
            withSlot(key) { it.reload(force = true) }.thenApply<SnapshotEntry<*>> { it }
        }

    /**
     * Compiles every app and tenant document again, installed or loading: for a family compiler whose inputs changed
     * (RFC 0007 §3.1 N3, the platform's limits and attributes). Call it after the change is visible to the compiler.
     *
     * Every load that starts after this call reads the store in full and compiles with the new inputs. A load in flight
     * may have compiled with the old ones, so it installs and another load follows it at once; a document installed
     * before is loaded again, [SnapshotOptions.reloadConcurrency] at a time. Until then the previous compilation is
     * served. A document that cannot be compiled again (the store cannot be read, the compile queue is full) fails
     * closed rather than keep the old compilation, and is retried. The future completes when every idle document has
     * been loaded again.
     */
    fun recompile(): CompletableFuture<Void> {
        context.compileEpoch.incrementAndGet()
        val idle = families.flatMap { it.slots.values }.filter { it.markRecompile() }
        return reloadAll(idle) { it.load() }
    }

    /** Revalidates every installed document with a conditional read, a few at a time. */
    fun revalidate(): CompletableFuture<Void> = reloadAll(installedSlots()) { it.load() }

    /**
     * Stops this source and answers everything waiting on it. Every step runs whatever the ones before it did, so a
     * store's subscription that cannot close leaves no thread behind (#1251); the first failure is thrown once they all
     * ran, with the others suppressed in it. A second call does nothing.
     */
    override fun close() {
        if (context.closed.get()) {
            return
        }
        context.closed.set(true)
        var failure: Throwable? = null
        for (step in closeSteps()) {
            failure = attemptClosing(step, failure)
        }
        failure?.let { throw it }
    }

    /**
     * Requests are answered before the loads end, so a request fails as closed ([load]) rather than with what its load
     * answers.
     */
    private fun closeSteps(): List<() -> Unit> = listOf(
        { periodic.cancel(false) },
        subscription::close,
        threads::stopScheduler,
        { pendingPrewarms.forEach { it.completeExceptionally(closedError()) } },
        { context.pendingRequests.forEach { it.abort(closedError()) } },
        ::endLoads,
        threads::stopHousekeeping,
        threads::stopCompiling,
        { threads.awaitOwned(closeTimeout) },
    )

    /** Runs the constructor's [step]; when it throws, [undo] and the executors this source made are released first. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> undoing(undo: () -> Unit, step: () -> T): T = try {
        step()
    } catch (e: Throwable) {
        listOf(undo, threads::stopScheduler, threads::stopHousekeeping, threads::stopCompiling)
            .forEach { release -> attemptClosing(release, e) }
        throw e
    }

    /** Runs [step]; what it throws becomes the failure, or is kept with the [failure] there already. */
    @Suppress("TooGenericExceptionCaught")
    private fun attemptClosing(step: () -> Unit, failure: Throwable?): Throwable? = try {
        step()
        failure
    } catch (error: Throwable) {
        if (failure != null && failure !== error) failure.addSuppressed(error)
        failure ?: error
    }

    /** With the scheduler gone nothing bounds a read the store does not answer, and everything waiting on it (#1266). */
    private fun endLoads() {
        shared.endLoad()
        families.forEach { family -> family.slots.values.forEach { it.endLoad() } }
    }

    /** Whether every thread this source started has finished: true once [close] has returned. */
    internal val threadsTerminated: Boolean
        get() = threads.terminated

    /**
     * One periodic tick: queue a lag report, then revalidate, unless a pass is in flight ([PeriodicPasses]). The future
     * completes when a pass that started after this call has ended.
     */
    internal fun tick(): CompletableFuture<Void> {
        lagReports.queue()
        return passes.tick()
    }

    /**
     * Reports every document lagging past [SnapshotOptions.lagWarn], at most once per [SnapshotOptions.lagLogInterval]
     * ([LagReports]). Off the scheduler thread: a log appender or a meter may take its time here.
     */
    internal fun reportLag() = lagReports.report()

    private fun closedError() = context.closedError()

    private fun schedulePeriodic(): ScheduledFuture<*> {
        val millis = options.revalidateInterval.toMillis()
        return threads.scheduler.scheduleWithFixedDelay(::periodicTick, millis, millis, TimeUnit.MILLISECONDS)
    }

    /** Never lets anything escape: a throwing task would silently cancel the periodic schedule. */
    @Suppress("TooGenericExceptionCaught")
    private fun periodicTick() {
        try {
            tick()
        } catch (error: Throwable) {
            threads.offTimer { log.error(error) { "Snapshot revalidation pass failed." } }
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
            val slot = family.slots.computeIfAbsent(id) { Slot(key, family.spec.compiler, context) }
            afterSlotLookup?.invoke(key)
            action(slot)?.let { return it }
            family.slots.remove(id, slot)
        }
    }

    private fun usableOf(key: SnapshotKey): CompletableFuture<out SnapshotEntry<*>> =
        if (key == sharedKey) shared.usable()!! else withSlot(key) { it.usable() }

    /** The family [key] belongs to; [IllegalArgumentException] when it is no document of this source. */
    private fun familyOf(key: SnapshotKey): Family<A> =
        families.firstOrNull { it.spec.kind == key.kind }
            ?: throw IllegalArgumentException("[$key] is not one of this source's documents.")

    private fun family(kind: SnapshotKind): Family<A> =
        families.firstOrNull { it.spec.kind == kind }
            ?: throw IllegalArgumentException("This source has no $kind documents.")

    private fun installedSlots(): List<Slot<*>> = buildList {
        if (shared.installed != null) add(shared)
        families.forEach { family -> family.slots.values.filterTo(this) { it.installed != null } }
    }

    /**
     * Runs [reload] on [slots], [SnapshotOptions.reloadConcurrency] at a time; a removed slot counts as done. Each lane
     * loops while reloads complete inline (a synchronous store), so the stack never grows with the number of slots. For a
     * periodic [pass], each slot that finishes is progress, and once it is abandoned no lane takes another slot. A lane
     * holds a read of the pass while it takes a slot, so the pass cannot be abandoned between that check and the read
     * the slot starts ([Pass.abandonIfStalled]).
     */
    private fun reloadAll(
        slots: List<Slot<*>>,
        pass: Pass? = null,
        reload: (Slot<*>) -> CompletableFuture<*>?,
    ): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        if (slots.isEmpty()) {
            done.complete(null)
            return done
        }
        val next = AtomicInteger()
        val remaining = AtomicInteger(slots.size)
        fun finishedOne() {
            pass?.progressed()
            if (remaining.decrementAndGet() == 0) {
                done.complete(null)
            }
        }
        fun lane() {
            while (true) {
                val holding = pass?.let { it.startRead() ?: return }
                val index = next.getAndIncrement()
                if (index >= slots.size) {
                    holding?.end()
                    return
                }
                val reloading = try {
                    reload(slots[index])
                } finally {
                    holding?.end()
                }
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

    /**
     * The periodic passes (#1215): one at a time. A tick that comes while a pass is in flight starts none, so a store
     * slower than the interval does not get another [SnapshotOptions.reloadConcurrency] reads each interval; it owes a
     * pass instead, which starts as soon as the one in flight ends, over every document installed by then. However many
     * ticks come meanwhile, one pass is owed. Nothing here waits: the scheduler thread only starts passes.
     *
     * A pass that has finished no document for [SnapshotOptions.storeReadTimeout] and has no read in flight is held only
     * by compilations. It is abandoned: it takes no more documents, and the tick starts a pass, so a compilation that
     * never ends does not hold back the revalidation of the documents that pass reaches (`SNP-6`). A pass whose reads are
     * slow but still finishing is never abandoned, and neither is one with a read in flight, so the reads of an abandoned
     * pass and of the one that replaced it never add up.
     */
    private inner class PeriodicPasses {
        // Guarded by this.
        private var current: Pass? = null
        private var owed: CompletableFuture<Void>? = null

        fun tick(): CompletableFuture<Void> {
            val now = nanoTime.asLong
            val stalled: Pass?
            val started: Pass
            synchronized(this) {
                val running = current
                // Abandoning is atomic with the pass's lanes taking a slot: once it returns true, no lane of it reads.
                if (running != null && !running.abandonIfStalled(now)) {
                    return owed ?: CompletableFuture<Void>().also { owed = it }
                }
                stalled = running
                // The pass that starts now is the one owed, if any: it starts after every tick that owed it.
                started = Pass(owed ?: CompletableFuture(), context)
                owed = null
                current = started
                stalled?.follow(started)
            }
            if (stalled != null) {
                threads.offTimer {
                    log.warn {
                        "A snapshot revalidation pass finished no document for ${options.storeReadTimeout} and has no " +
                            "read in flight: compilations hold it. Starting the next pass without it."
                    }
                }
            }
            run(started)
            return started.done
        }

        @Suppress("TooGenericExceptionCaught")
        private fun run(pass: Pass) {
            val reloads = try {
                reloadAll(installedSlots(), pass) { it.load(pass = pass) }
            } catch (error: Throwable) {
                // Never leave a pass in flight that nothing will end: every later tick would only owe one.
                pass.done.completeExceptionally(error)
                ended(pass)
                throw error
            }
            reloads.whenComplete { _, error ->
                if (error != null) pass.done.completeExceptionally(error) else pass.done.complete(null)
                ended(pass)
            }
        }

        /** [pass] ended: the owed pass starts now, if any. An abandoned pass ends with nothing to start. */
        private fun ended(pass: Pass) {
            val next = synchronized(this) {
                if (current !== pass) {
                    return
                }
                val waiting = owed
                if (waiting == null) {
                    current = null
                    return
                }
                owed = null
                Pass(waiting, context).also { current = it }
            }
            run(next)
        }
    }

    private fun attemptPrewarm(keys: List<SnapshotKey>, backoff: Duration, done: CompletableFuture<Void>) {
        if (context.closed.get()) {
            done.completeExceptionally(closedError())
            return
        }
        val loads = keys.map { usableOf(it) }
        CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, _ ->
            if (context.closed.get()) {
                done.completeExceptionally(closedError())
                return@whenComplete
            }
            val answered = loads.filterNot { it.isCompletedExceptionally }.map { it.join() }
            val failed = answered.filter { it.state is DocumentState.Failed }
            if (failed.isEmpty() && answered.size == loads.size) {
                prewarmed = true
                done.complete(null)
                return@whenComplete
            }
            threads.offRequestThread { log.warn { "Snapshot prewarm failed for ${failed.map { it.key }}; retrying in $backoff." } }
            val nextBackoff = minOf(backoff.multipliedBy(2), options.prewarmMaxBackoff)
            try {
                threads.scheduler.schedule(
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

    internal companion object {
        private val log = KotlinLogging.logger {}

        fun unwrap(error: Throwable?): Throwable {
            var current = error ?: return IllegalStateException("The load completed without a result.")
            while (current is CompletionException || current is ExecutionException) {
                current = current.cause ?: return current
            }
            return current
        }

        fun Throwable.isFormatFailure(): Boolean =
            generateSequence(this) { it.cause }.take(16).any { it is SnapshotFormatException }

        /** How long [close] waits for this source's threads to finish what was queued. */
        val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
