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
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Ask
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * One key's [KeyTransitions] against a recorded world (RFC 0013 B2b, step 1): it offers events, executes the effects
 * as bookkeeping (reads and compilations outstanding, retries due, who waits on which handle, what each handle was
 * answered), and checks every step against what the page promises. [violations] collects what it found; [copy] forks
 * the whole world, so a search can try every next event from one point.
 */
internal class KeyHarness private constructor(override val config: KeyConfig) : KeyWorld {
    val transitions = KeyTransitions<String>(config)
    var state: KeyState<String> = transitions.initial()
    override var epoch = 0L
    override var closed = false
    var nanos = 0L
    var seen: Seen? = null

    /** Loads whose read the store has not answered; a read of an ended load may still answer. */
    val reads = sortedSetOf<Long>()
    val compiles = sortedSetOf<Long>()
    var retries = 0

    /** The handle each waiter hangs on, and what was answered to whom directly. */
    val hung = linkedMapOf<HarnessWaiter, KeyHandle<String>>()
    val direct = linkedMapOf<HarnessWaiter, KeyEffect<String>>()
    val answered = linkedMapOf<KeyHandle<String>, SnapshotEntry<String>>()

    /** A reload's handle must be answered by a load numbered above this: one that started after the reload. */
    override val startedAfter = linkedMapOf<KeyHandle<String>, Long>()
    val installs = mutableListOf<SnapshotEntry<String>>()
    val violations = mutableListOf<String>()
    val log = mutableListOf<String>()
    private var waiters = 0

    /** The last number given out before the step being executed: a load numbered above it started after the event. */
    private var serialBefore = 0L

    override fun fail(message: String) {
        violations += "$message (after ${log.lastOrNull()})"
    }

    fun moment() = KeyMoment(Instant.EPOCH, nanos, epoch, closed, seen)

    fun waiter(ask: Ask) = HarnessWaiter(++waiters, ask)

    fun request(ask: Ask): HarnessWaiter = waiter(ask).also { offer(KeyEvent.Requested(ask, it)) }

    /** Offers [event] and executes what its transition returns; the checks run on the way. */
    fun offer(event: KeyEvent<String>): KeyStep<String> {
        log += describe(event)
        if (event == KeyEvent.Close) closed = true
        if (event is KeyEvent.ReadDone || event is KeyEvent.ReadFailed || event is KeyEvent.ReadTimedOut) reads -= event.loadId!!
        if (event is KeyEvent.Compiled || event is KeyEvent.CompileCrashed || event is KeyEvent.CompileRejected) compiles -= event.loadId!!
        if (event is KeyEvent.RetryDue) retries--
        val before = state
        val step = transitions.next(before, event, moment())
        val again = transitions.next(before, event, moment())
        if (again.toString().replace(HASH, "@") != step.toString().replace(HASH, "@")) violations += "the same event in the same state gave another step: $again"
        KeyStepChecks(this, before, event, step).run()
        serialBefore = before.serial
        state = step.state
        step.effects.forEach(::execute)
        return step
    }

    /** Recompile's own step: the epoch moves, then the key is told. */
    fun recompile(): Boolean {
        epoch++
        val reply = java.util.concurrent.CompletableFuture<Boolean>()
        offer(KeyEvent.Recompile(reply))
        return reply.getNow(null) ?: false.also { violations += "a recompile was not replied to" }
    }

    /** Answers every read and compilation, and runs every retry, until nothing is outstanding. */
    fun settle(answer: SnapshotRead = SnapshotRead.Absent) {
        repeat(SETTLE_LIMIT) {
            val load = state.load
            when {
                load != null && load.id in reads -> offer(KeyEvent.ReadDone(load.id, answer))
                load != null && load.id in compiles -> offer(
                    KeyEvent.Compiled(load.id, DocumentState.Present("compiled"))
                )
                reads.isNotEmpty() -> offer(KeyEvent.ReadDone(reads.first(), answer))
                retries > 0 && !closed -> offer(KeyEvent.RetryDue())
                else -> return
            }
        }
        violations += "the key did not settle within $SETTLE_LIMIT events"
    }

    /** Once nothing is outstanding: every waiter was answered, once, and every reload by a load that started after it. */
    fun checkAllAnswered() {
        if (state.load != null) violations += "settled with a load in flight: ${state.load}"
        if (state.next != null) violations += "settled with a pending next handle"
        for ((waiter, handle) in hung) {
            if (handle !in answered) violations += "$waiter hangs on $handle, which was never answered"
        }
    }

    fun copy(): KeyHarness = KeyHarness(config).also {
        it.state = state
        it.epoch = epoch
        it.closed = closed
        it.nanos = nanos
        it.seen = seen
        it.reads += reads
        it.compiles += compiles
        it.retries = retries
        it.hung += hung
        it.direct += direct
        it.answered += answered
        it.startedAfter += startedAfter
        it.installs += installs
        it.violations += violations
        it.log += log
        it.waiters = waiters
    }

    private fun execute(effect: KeyEffect<String>) {
        when (effect) {
            is KeyEffect.StartRead -> reads += effect.loadId
            is KeyEffect.Compile -> compiles += effect.loadId
            is KeyEffect.RecordSeen -> seen = if (effect.ifAbsent) seen ?: effect.seen else effect.seen
            is KeyEffect.Install -> installs += effect.entry
            is KeyEffect.ScheduleRetry -> retries++
            is KeyEffect.Reply -> effect.reply.complete(effect.value)
            else -> answer(effect)
        }
    }

    /** The effects that answer someone: a handle's waiters, or one waiter directly. */
    private fun answer(effect: KeyEffect<String>) {
        when (effect) {
            is KeyEffect.Answer -> {
                if (effect.handle in answered) violations += "${effect.handle} answered twice"
                answered[effect.handle] = effect.entry
            }

            is KeyEffect.Hang -> hang(effect.waiter as HarnessWaiter, effect.handle)
            is KeyEffect.Serve -> answerDirectly(effect.waiter, effect)
            is KeyEffect.Refuse -> answerDirectly(effect.waiter, effect)
            is KeyEffect.Skip -> answerDirectly(effect.waiter, effect)
            is KeyEffect.Redispatch -> answerDirectly(effect.event.waiter!!, effect)
            else -> Unit
        }
    }

    /** [event] for the log: a pass is named, not printed, since a mock records each call made to it, `toString` too. */
    private fun describe(event: KeyEvent<String>): String = when (event) {
        is KeyEvent.Requested -> "Requested(ask=${describe(event.ask)}, waiter=${event.waiter})"
        else -> event.toString()
    }

    private fun hang(waiter: HarnessWaiter, handle: KeyHandle<String>) {
        if (handle in answered) violations += "$waiter hung on $handle, already answered"
        hung[waiter] = handle
        val ask = waiter.ask
        val passFollows = ask is Ask.Load && ask.pass != null && handle !== state.load?.handle
        if (ask is Ask.Reload || ask is Ask.ReloadIfHeld || passFollows) {
            startedAfter.merge(handle, serialBefore, ::maxOf)
        }
    }

    private fun answerDirectly(waiter: KeyEvent.Waiter, effect: KeyEffect<String>) {
        direct[waiter as HarnessWaiter] = effect
    }

    /** A waiter in these tests: numbered, and remembering what it asked. */
    class HarnessWaiter(val number: Int, val ask: Ask) : KeyEvent.Waiter {
        override fun toString(): String = "waiter#$number(${describe(ask)})"
    }

    companion object {
        val KEY: SnapshotKey = SnapshotKey.App("app")
        val STORE_READ_TIMEOUT: Duration = Duration.ofSeconds(3)
        private const val SETTLE_LIMIT = 64

        /** [ask] for a log line, with its pass named rather than printed. */
        fun describe(
            ask: Ask
        ): String = if (ask is Ask.Load && ask.pass != null) "Load(force=${ask.force}, pass)" else ask.toString()

        /** An identity hash in a `toString`: two equal steps differ only there. */
        private val HASH = Regex("@[0-9a-f]+")

        fun of(shared: Boolean = false, localCopyMaxAge: Duration = Duration.ZERO) = KeyHarness(
            KeyConfig(
                key = KEY,
                shared = shared,
                storeReadTimeout = STORE_READ_TIMEOUT,
                rejectedReloadRetry = Duration.ofMillis(100),
                revalidateInterval = Duration.ofSeconds(1),
                localCopyMaxAge = localCopyMaxAge,
            )
        )

        /** A periodic pass that started at [nanos], on a clock that stays there. */
        fun passStartedAt(nanos: Long): Pass = Pass(CompletableFuture(), { nanos }, STORE_READ_TIMEOUT)

        fun present(revision: Long): SnapshotRead.Present {
            val document = SnapshotDocument(
                SnapshotDocument.DOCUMENT_SCHEMA,
                revision,
                Instant.EPOCH,
                "r$revision".toByteArray()
            )
            return SnapshotRead.Present(document, InMemorySnapshotDocumentStore.digestOf(document))
        }
    }
}
