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

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Ask
import me.ahoo.cosec.runtime.snapshot.KeyModelScript.Kind
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one [KeyModelScript] through [KeyTransitions] and [KeyModelRunner] on [SlotModelThreads] and [SlotModelStore]
 * (RFC 0013 B2b, step 1): the key-level driver of the slot model. After every event: [KeyStepChecks] on each step
 * taken, at most one live read for the key, and what is published equal to what the state holds; once the run
 * settles, or the key closes, every waiter answered exactly once. [failure] is the first violation, with [log].
 */
internal class KeyModelRun(private val script: KeyModelScript) : KeyModelRunner.KeyModelWorld, KeyWorld {
    override val key: SnapshotKey = SnapshotKey.App("app")
    override val threads = SlotModelThreads()
    private val events = longArrayOf(0)
    override val store = SlotModelStore({ events[0] }, { threads.nanos })
    override val storeReadTimeout: Duration = Duration.ofSeconds(3)
    override val counts = sortedMapOf<String, Int>()
    override var seen: Seen? = null
    override var epoch = 0L
    override var closed = false
    override val startedAfter = HashMap<KeyHandle<String>, Long>()
    override val config = KeyConfig(
        key,
        false,
        storeReadTimeout,
        Duration.ofMillis(100),
        Duration.ofSeconds(1),
        Duration.ZERO
    )
    private val transitions = KeyTransitions<String>(config)
    private val pass = mockk<Pass>(relaxed = true)
    private val coldLoadTimeout = Duration.ofSeconds(5)

    /** The runner the family's map holds for the key; a dropped one is replaced by a new one on the next request. */
    private var current: KeyModelRunner? = null
    private val runners = mutableListOf<KeyModelRunner>()
    private val waiters = mutableListOf<ScriptWaiter>()
    private val loadOfRead = HashMap<SlotModelStore.Read, Pair<KeyModelRunner, Long>>()
    private val random = kotlin.random.Random(script.seed)
    private var failNext = false
    private var overflowNext = false

    val log = mutableListOf<String>()
    var failure: String? = null
        private set

    fun run(): KeyModelRun {
        store.put(key)
        for ((index, event) in script.events.withIndex()) {
            if (!step("e$index $event") { apply(event) }) return this
        }
        if (!step("settle") { settle() }) return this
        step("answered") { allAnswered("once the run settled") }
        return this
    }

    private fun step(label: String, action: () -> Unit): Boolean {
        events[0]++
        log += label
        @Suppress("TooGenericExceptionCaught")
        try {
            action()
        } catch (error: Throwable) {
            fail("threw $error")
        }
        threads.thrown.filterNot { it is StackOverflowError }.forEach { fail("a task threw $it") }
        threads.thrown.clear()
        afterEvent()
        return failure == null
    }

    override fun fail(message: String) {
        if (failure == null) failure = "$message (after ${log.lastOrNull()})"
    }

    private fun apply(event: KeyModelScript.Event) {
        when (event.kind) {
            Kind.REQUEST -> request(Ask.Request)
            Kind.USABLE -> request(Ask.Usable)
            Kind.LOAD -> request(Ask.Load(force = false))
            Kind.PASS -> request(Ask.Load(force = false, pass = pass))
            Kind.RELOAD -> request(Ask.Reload(force = false))
            Kind.RELOAD_FORCED -> request(Ask.Reload(force = true))
            Kind.RELOAD_IF_HELD -> request(Ask.ReloadIfHeld(force = false))
            Kind.RECOMPILE -> recompile()
            Kind.EVICT -> evict(claims = true)
            Kind.EVICT_AT_BOUND -> evict(claims = false)
            Kind.STALE_LOOKUP -> staleLookup()
            Kind.CLOSE -> close()
            else -> onWorld(event)
        }
    }

    /** What happens around the key: the store, its reads, the compile pool, housekeeping and time. */
    private fun onWorld(event: KeyModelScript.Event) {
        when (event.kind) {
            Kind.CHANGED -> changed { store.put(key) }
            Kind.PUT_SILENT -> store.put(key)
            Kind.REMOVE -> changed { store.remove(key) }
            Kind.CORRUPT -> changed { store.corrupt(key) }
            Kind.REGRESS -> changed { store.regress(key) }
            Kind.RELEASE, Kind.RELEASE_LATE -> pick(
                store.pending.filterNot {
                    it.hung
                },
                event.pick
            )?.release(atStart = event.kind == Kind.RELEASE)
            Kind.FAIL_READS -> store.failure = IllegalStateException("scripted: the store cannot be read")
            Kind.HEAL_READS -> store.failure = null
            Kind.HANG -> pick(store.pending, event.pick)?.hung = true
            Kind.ADVANCE -> threads.advance(Duration.ofMillis(event.millis))
            else -> onThreads(event)
        }
    }

    private fun onThreads(event: KeyModelScript.Event) {
        when (event.kind) {
            Kind.COMPILE -> if (threads.compiles.isNotEmpty()) threads.runCompile(event.pick % threads.compiles.size)
            Kind.COMPILE_FAILS -> failNext = true
            Kind.COMPILE_OVERFLOWS -> overflowNext = true
            Kind.REJECT_COMPILES -> threads.rejectCompiles = true
            Kind.ACCEPT_COMPILES -> threads.rejectCompiles = false
            Kind.HOUSEKEEP -> with(
                threads.housekeeping.tasks
            ) { if (isNotEmpty()) threads.runHousekeeping(event.pick % size) }
            else -> error("not an event of the world: $event")
        }
    }

    // ---- What a caller does: create, register and time its waiter before it offers (§2, condition 1).

    private fun runner(): KeyModelRunner = current?.takeUnless { it.dropped } ?: KeyModelRunner(this, transitions).also {
        current = it
        runners += it
    }

    private fun request(ask: Ask, to: KeyModelRunner = runner()) {
        val waiter = ScriptWaiter(waiters.size + 1, ask).also { waiters += it }
        if (ask == Ask.Request) {
            threads.scheduler.schedule(
                Runnable { waiter.settle("timeout") },
                coldLoadTimeout.toNanos(),
                TimeUnit.NANOSECONDS
            )
        }
        to.offer(KeyEvent.Requested(ask, waiter))
    }

    /** The runner is looked up, an eviction removes it, then the request is offered to it: it goes to the key's next. */
    private fun staleLookup() {
        val looked = runner()
        evict(claims = true)
        request(Ask.Request, looked)
    }

    private fun changed(write: () -> Unit) {
        write()
        runner().offer(KeyEvent.Changed)
    }

    private fun recompile() {
        epoch++
        val reply = CompletableFuture<Boolean>()
        runner().offer(KeyEvent.Recompile(reply))
        if (!reply.isDone) fail("a recompile was not replied to")
        // recompile() reloads what is outdated, as SnapshotSource does.
        if (reply.getNow(false)) request(Ask.ReloadIfHeld(force = false))
    }

    private fun evict(claims: Boolean) {
        val runner = current ?: return
        val absent = runner.installed?.origin?.let { it == EntryOrigin.ABSENT } ?: false
        val reply = CompletableFuture<Boolean>()
        runner.offerEvict(KeyModelRunner.EvictRequest(false, absent, { claims }, reply))
        counts.merge(if (reply.getNow(false)) "eviction claimed" else "eviction refused", 1, Int::plus)
    }

    private fun close() {
        closed = true
        runners.forEach { it.offer(KeyEvent.Close) }
        // close() answers every request still waiting, as SnapshotSource does.
        waiters.filter { it.ask == Ask.Request }.forEach { it.settle("close") }
        allAnswered("after close")
    }

    // ---- The world a runner executes its effects in.

    override fun moment() = KeyMoment(threads.clock.instant(), threads.nanos, epoch, closed, seen)

    override fun checkStep(before: KeyState<String>, event: KeyEvent<String>, step: KeyStep<String>) {
        counts.merge("steps checked", 1, Int::plus)
        counts.merge("event ${event.javaClass.simpleName}", 1, Int::plus)
        step.effects.filterIsInstance<KeyEffect.Hang<String>>().filter {
            (it.waiter as ScriptWaiter).ask.let { ask -> ask is Ask.Reload || ask is Ask.ReloadIfHeld }
        }.forEach {
            startedAfter.merge(it.handle, before.serial, ::maxOf)
        }
        KeyStepChecks(this, before, event, step).run()
    }

    override fun readStarted(runner: KeyModelRunner, loadId: Long) {
        loadOfRead[store.reads.last()] = runner to loadId
    }

    override fun compile(read: SnapshotRead.Present): Any = when {
        overflowNext -> StackOverflowError("scripted").also { overflowNext = false }
        failNext -> IllegalStateException("scripted: does not compile").also { failNext = false }
        else -> "c${read.document.revision}"
    }

    override fun installed(entry: SnapshotEntry<String>) {
        counts.merge("installs", 1, Int::plus)
    }

    override fun answer(waiter: KeyEvent.Waiter, entry: SnapshotEntry<String>?, handle: KeyHandle<String>?) =
        (waiter as ScriptWaiter).settle(if (handle == null) "directly" else "its handle")

    override fun refuse(waiter: KeyEvent.Waiter) = (waiter as ScriptWaiter).settle("refused")

    override fun skip(waiter: KeyEvent.Waiter) = (waiter as ScriptWaiter).settle("skipped")

    override fun redispatch(event: KeyEvent.Requested) {
        counts.merge("redispatched", 1, Int::plus)
        runner().offer(event)
    }

    override fun dropIfEmpty(runner: KeyModelRunner) =
        runner.offerEvict(KeyModelRunner.EvictRequest(true, null, { false }, CompletableFuture()))

    override fun evictBeyond(runner: KeyModelRunner) {
        val absent = runner.installed?.origin?.let { it == EntryOrigin.ABSENT } ?: return
        runner.offerEvict(KeyModelRunner.EvictRequest(false, absent, { random.nextBoolean() }, CompletableFuture()))
    }

    // ---- The checks.

    private fun afterEvent() {
        for (runner in runners) {
            if (runner.installed !== runner.state.installed) {
                fail(
                    "published ${runner.installed}, but the state holds ${runner.state.installed}"
                )
            }
            val reading = runner.state.load?.takeIf { it.phase == LoadPhase.Reading }
            if (reading != null && threads.nanos - reading.startedNanos > storeReadTimeout.toNanos()) {
                fail("load ${reading.id} still reads after the store read timeout")
            }
            val live = store.pending.count { loadOfRead[it]?.let { (r, id) -> r === runner && r.state.load?.id == id } == true }
            if (live > 1) fail("$live live reads of one key")
        }
    }

    private fun allAnswered(label: String) {
        waiters.filterNot { it.answered.get() }.forEach { fail("$it was not answered $label") }
        waiters.filter { it.times > 1 }.forEach { fail("$it was answered ${it.times} times") }
    }

    /** Lets every read answer and every queued task run, firing each one-shot timer, until nothing waits. */
    private fun settle() {
        store.failure = null
        threads.rejectCompiles = false
        failNext = false
        overflowNext = false
        val horizon = threads.nanos + Duration.ofMinutes(2).toNanos()
        repeat(SETTLE_LIMIT) {
            when {
                store.pending.isNotEmpty() -> store.pending.first().release(atStart = false)
                threads.compiles.isNotEmpty() -> threads.runCompile(0)
                threads.housekeeping.tasks.isNotEmpty() -> threads.runHousekeeping(0)
                else -> {
                    val next = threads.nextOneShot()?.takeIf { it <= horizon } ?: return
                    threads.advance(Duration.ofNanos(next - threads.nanos))
                }
            }
        }
        fail("the run did not settle within $SETTLE_LIMIT steps")
    }

    private fun <T> pick(from: List<T>, pick: Int): T? = if (from.isEmpty()) null else from[pick % from.size]

    /** One waiter: answered once, by whichever of its handle, its timeout or close comes first, as a view is. */
    private inner class ScriptWaiter(val number: Int, val ask: Ask) : KeyEvent.Waiter {
        val answered = AtomicBoolean()
        var times = 0

        fun settle(by: String) {
            if (by != "timeout" && by != "close") times++
            if (answered.compareAndSet(false, true)) counts.merge("answered $by", 1, Int::plus)
        }

        override fun toString(): String = "waiter#$number(${KeyHarness.describe(ask)})"
    }

    private companion object {
        const val SETTLE_LIMIT = 10_000
    }
}
