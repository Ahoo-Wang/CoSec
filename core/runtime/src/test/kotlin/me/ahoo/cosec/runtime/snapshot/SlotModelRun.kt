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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SlotModelScript.Companion.FAMILY
import me.ahoo.cosec.runtime.snapshot.SlotModelScript.Companion.SHARED
import me.ahoo.cosec.runtime.snapshot.SlotModelScript.Kind
import me.ahoo.cosec.runtime.snapshot.SlotModelScript.On
import me.ahoo.cosec.runtime.snapshot.SlotModelScript.Subscribe
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one [SlotModelScript] against a real [SnapshotSource] whose every thread is [SlotModelThreads], checking the
 * [SlotModel] after each event. [failure] is the first violation, with the event it followed; [reached] says which
 * kinds of event the run got to, for the vacuity report.
 */
internal class SlotModelRun(private val script: SlotModelScript) {
    private val threads = SlotModelThreads()
    private val options = SnapshotOptions(
        maxApps = 1,
        maxTenants = 1,
        reloadConcurrency = script.lanes,
    )
    private val model: SlotModel
    private val store: SlotModelStore
    private val compiler: SlotModel.Compiler
    private lateinit var source: SnapshotSource<ModelCompiled, ModelCompiled>
    private var dropNext = false

    /** The events run so far, for the failure message. */
    val log = mutableListOf<String>()
    var failure: String? = null
        private set
    val reached = sortedSetOf<String>()
    val counts: SlotModel.Counts get() = model.counts

    /** The event now running, which the store stamps each read with. */
    private val moment = longArrayOf(0)

    init {
        store = SlotModelStore({ moment[0] }, { threads.nanos })
        model = SlotModel(store, threads, options, FAMILY)
        compiler = model.Compiler(::recompile)
        threads.onPeriodic = { model.masks(null) }
    }

    /** Runs the script; stops at the first violation. */
    fun run(): SlotModelRun {
        script.initial.forEach(store::put)
        store.duringSubscribe = ::duringSubscribe
        val ticker = AtomicLong()
        if (!step("construct") { construct(ticker) }) return this
        for ((index, event) in script.events.withIndex()) {
            if (!step("e$index $event") { apply(event) }) return this
        }
        step("settle") { settle() }
        if (failure == null) step("resets") { model.checkResets() }
        if (failure == null && model.closedAt < 0) {
            val lag = SlotModelLag(script, store, threads, model, options, ::checkNow)
            step("lag") { lag.run() }
        }
        if (failure == null && model.closedAt < 0) step("converge") { converge() }
        if (failure == null) step("answered") { model.allAnswered("once the run settled") }
        if (store.reads.any { it.failed }) reached += "store read failed"
        if (threads.rejected > 0) reached += "compile rejected"
        return this
    }

    private fun duringSubscribe(listener: SnapshotChangeListener) {
        when (script.duringSubscribe) {
            Subscribe.NOTHING -> return
            Subscribe.RESET -> listener.onReset()
            Subscribe.SHARED_CHANGED -> listener.onChanged(listOf(SHARED))
            Subscribe.APP_CHANGED -> listener.onChanged(listOf(APP_A))
            Subscribe.TENANT_CHANGED -> listener.onChanged(listOf(TENANT_A))
        }
        reached += "listener called in subscribe"
    }

    private fun construct(ticker: AtomicLong) {
        source = SnapshotSource(
            store,
            SHARED,
            compiler,
            DocumentSnapshots.families(compiler, options),
            options,
            threads.clock,
            model.metrics,
            threads.compileExecutor,
            threads.scheduler,
            ticker::incrementAndGet,
            threads.housekeeping,
        )
        source.nanoTime = threads.nanoTime
    }

    /** One event, then the model's checks; false once a check failed. */
    @Suppress("TooGenericExceptionCaught")
    private fun step(label: String, action: () -> Unit): Boolean {
        model.event = ++moment[0]
        log += label
        try {
            action()
        } catch (error: Throwable) {
            model.violations += "$label threw $error"
        }
        threads.thrown.filterNot { it is StackOverflowError }.forEach { model.violations += "a task threw $it" }
        threads.thrown.clear()
        if (::source.isInitialized) {
            model.afterEvent(source.entries(), model.closedAt >= 0 || closed)
            model.checkHeld(source.entries(), source::heldCounts)
        }
        if (model.violations.isEmpty()) return true
        failure = "${model.violations.first()} (after ${log.last()})"
        return false
    }

    private var closed = false

    private fun apply(event: SlotModelScript.Event) = when (event.kind.on) {
        On.STORE -> onStore(event)
        On.SOURCE -> onSource(event)
        On.THREADS -> onThreads(event)
    }

    private fun onStore(event: SlotModelScript.Event) {
        val key = event.key
        when (event.kind) {
            Kind.PUT -> write(key!!) { store.put(key) }
            Kind.REMOVE -> write(key!!) { store.remove(key) }
            Kind.CORRUPT -> write(key!!) { store.corrupt(key) }
            Kind.REGRESS -> write(key!!) { if (store.regress(key)) reached += "store regressed" }
            Kind.FAIL_READS -> store.failure = IllegalStateException("scripted: the store cannot be read")
            Kind.HEAL_READS -> store.failure = null
            Kind.DROP_NEXT_NOTIFICATION -> dropNext = true
            Kind.RESET -> {
                model.reset()
                store.reset()
            }
            Kind.RELEASE, Kind.RELEASE_LATE -> release(event)
            Kind.HANG -> pick(store.pending, event.pick)?.hung = true
            else -> error("not a store event: $event")
        }
    }

    private fun onSource(event: SlotModelScript.Event) {
        val key = event.key
        masks(event)
        when (event.kind) {
            Kind.REQUEST -> request(key!!, event.pick)
            Kind.REQUEST_SHARED -> model.handedOut("request", setOf(SHARED), options.coldLoadTimeout) { source.load() }
            Kind.REVALIDATE -> model.handedOut("revalidate", model.installedKeys()) { source.revalidate() }
            Kind.RECOMPILE -> recompile()
            Kind.RELOAD -> model.handedOut("reload", setOf(key!!)) { source.reload(key) }
            Kind.SETTLED -> model.handedOut("settled", setOf(key!!)) { source.settled(listOf(key)) }
            Kind.PREWARM -> model.handedOut("prewarm", setOf(SHARED, key!!)) { source.prewarm(listOf(SHARED, key)) }
            Kind.TICK -> model.handedOut("tick", emptySet()) { source.tick() }
            Kind.PEEK -> source.peekAll(listOf(key!!))
            Kind.CLOSE -> close()
            else -> error("not a source event: $event")
        }
    }

    private fun onThreads(event: SlotModelScript.Event) {
        when (event.kind) {
            Kind.COMPILE -> if (threads.compiles.isNotEmpty()) threads.runCompile(event.pick % threads.compiles.size)
            Kind.COMPILE_FAILS -> compiler.failNext = true
            Kind.COMPILE_OVERFLOWS -> compiler.overflowNext = true
            Kind.COMPILE_RECOMPILES -> compiler.recompileDuringNext = true
            Kind.REJECT_COMPILES -> threads.rejectCompiles = true
            Kind.ACCEPT_COMPILES -> threads.rejectCompiles = false
            Kind.HOUSEKEEP -> with(threads.housekeeping.tasks) {
                if (isNotEmpty()) threads.runHousekeeping(event.pick % size)
            }
            Kind.ADVANCE -> threads.advance(Duration.ofMillis(event.millis))
            else -> error("not a threads event: $event")
        }
    }

    /** A held read answers: with what the key held when it started, or, released late, with what it holds now. */
    private fun release(event: SlotModelScript.Event) {
        val read = pick(store.pending.filterNot { it.hung }, event.pick) ?: return
        if (model.ended(read)) reached += "abandoned read answered"
        read.release(atStart = event.kind == Kind.RELEASE)
    }

    /**
     * What else than a reset may read a key during [event]: a reset is only shown to reload a key when nothing else
     * started a read of it since ([SlotModel.checkResets]).
     */
    private fun masks(event: SlotModelScript.Event) {
        when (event.kind) {
            Kind.REVALIDATE, Kind.RECOMPILE, Kind.TICK -> model.masks(null)
            Kind.REQUEST, Kind.RELOAD, Kind.SETTLED, Kind.PREWARM -> {
                model.masks(event.key)
                model.masks(SHARED)
            }
            Kind.REQUEST_SHARED -> model.masks(SHARED)
            else -> Unit
        }
    }

    private fun write(key: SnapshotKey, change: () -> Unit) {
        if (!dropNext) model.masks(key)
        store.dropNotifications = dropNext
        dropNext = false
        change()
        store.dropNotifications = false
    }

    private fun <T> pick(from: List<T>, pick: Int): T? = if (from.isEmpty()) null else from[pick % from.size]

    private fun request(key: SnapshotKey, pick: Int) {
        val keys = setOf(SHARED, key)
        if (key is SnapshotKey.App && pick % 2 == 0) {
            model.handedOut("request", keys, options.coldLoadTimeout) { source.load(key.appId) }
        } else {
            model.handedOut("request", keys, options.coldLoadTimeout) { source.loadAll(listOf(key)) }
        }
    }

    /** The family compiler's inputs change: the model's first, then the source is told, as a host does. */
    private fun recompile() {
        val keys = model.recompiledKeys()
        model.masks(null)
        model.recompile()
        reached += "recompile"
        model.handedOut("recompile", keys) { source.recompile() }
    }

    private fun close() {
        val busy = store.pending.any {
            !it.abandoned
        } || threads.compiles.isNotEmpty() || model.obligations.any { !it.future.isDone }
        if (busy) reached += "close mid-load"
        // Closing answers the waiters while it runs: from its start, they may be answered without a read.
        closed = true
        model.closedAt = model.event
        source.close()
        model.allAnswered("after close")
    }

    /** The model's checks after what just ran, between the steps of an event; false once one failed. */
    private fun checkNow(): Boolean {
        threads.thrown.filterNot { it is StackOverflowError }.forEach { model.violations += "a task threw $it" }
        threads.thrown.clear()
        model.afterEvent(source.entries(), closed)
        model.checkHeld(source.entries(), source::heldCounts)
        return model.violations.isEmpty()
    }

    /** Lets every read answer and every queued task run, firing each one-shot timer, until nothing waits. */
    private fun settle() {
        store.failure = null
        threads.rejectCompiles = false
        compiler.failNext = false
        compiler.overflowNext = false
        compiler.recompileDuringNext = false
        val horizon = threads.nanos + HORIZON.toNanos()
        repeat(SETTLE_LIMIT) {
            when {
                store.pending.isNotEmpty() -> store.pending.first().release(atStart = false)
                threads.compiles.isNotEmpty() -> threads.runCompile(0)
                threads.housekeeping.tasks.isNotEmpty() -> threads.runHousekeeping(0)
                else -> {
                    val next = threads.nextOneShot()?.takeIf { it <= horizon } ?: return
                    // Without the periodic pass: it would read every key, and hide a reset that read none.
                    threads.advance(Duration.ofNanos(next - threads.nanos), periodic = false)
                }
            }
            threads.thrown.filterNot { it is StackOverflowError }.forEach { model.violations += "a task threw $it" }
            threads.thrown.clear()
            model.afterEvent(source.entries(), closed)
            model.checkHeld(source.entries(), source::heldCounts)
            if (model.violations.isNotEmpty()) return
        }
        model.violations += "the run did not settle within $SETTLE_LIMIT steps"
    }

    /** Every document read again, then each loaded alone: each must serve what the store holds now. */
    private fun converge() {
        source.revalidate()
        settle()
        for (key in FAMILY) {
            val loading = source.loadAll(listOf(key))
            settle()
            val set = loading.getNow(null) ?: return run { model.violations += "a load of $key did not settle" }
            model.converged(SHARED, set.shared)
            model.converged(key, set.scoped.single())
        }
        reached += "converged"
    }

    private companion object {
        const val SETTLE_LIMIT = 10_000

        /** How far settling moves time: past every timeout and retry, not forever after a prewarm that cannot end. */
        val HORIZON: Duration = Duration.ofMinutes(2)
    }
}
