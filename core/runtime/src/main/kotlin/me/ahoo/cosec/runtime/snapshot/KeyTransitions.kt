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
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Claim
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Waiter
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.util.concurrent.TimeoutException

/**
 * One key's transitions as a pure function (RFC 0013 B2b, step 1): [next] takes the state, one event and the moment it
 * is taken at, and returns the next state with the effects to execute in order. It does no I/O, reads no clock, takes
 * no lock and touches no shared state: everything it needs from outside is in the [KeyMoment], and everything it does
 * to the outside is an effect. It decides as [Slot] does, and replaces it once a runner executes it.
 *
 * Every waiter hangs on exactly one [KeyHandle], named by the transition that takes its request: the load in flight's,
 * a new load's, or, for a reload, the handle of the load that starts after the current one (`SNP-13`). A handle is
 * answered once, by the transition that ends its load.
 */
internal class KeyTransitions<T : Any>(private val config: KeyConfig) {
    private val loads = KeyLoads<T>(config)

    fun initial(): KeyState<T> = KeyState(retryDelay = config.rejectedReloadRetry)

    fun next(state: KeyState<T>, event: KeyEvent<T>, moment: KeyMoment): KeyStep<T> =
        KeyStepBuilder(state, moment).also { next(it, state, event, moment) }.build()

    /** [next] into a drain's own [step], reset here: the same decision, its effects read in place. */
    fun next(step: KeyStepBuilder<T>, state: KeyState<T>, event: KeyEvent<T>, moment: KeyMoment) {
        step.reset(state, if (event == KeyEvent.Close) moment.closing() else moment)
        when (event) {
            is KeyEvent.Requested -> requested(step, event)
            KeyEvent.Changed -> changed(step)
            KeyEvent.RetryDue -> if (!state.removed) reload(step, force = false, waiter = null)
            is KeyEvent.Recompile -> step.emit(KeyEffect.Reply(event.reply, recompile(step)))
            is KeyEvent.Evict -> evict(step, event)
            KeyEvent.Close -> close(step)
            is KeyEvent.ReadDone, is KeyEvent.ReadFailed, is KeyEvent.ReadTimedOut, is KeyEvent.Compiled,
            is KeyEvent.CompileCrashed, is KeyEvent.CompileRejected, is KeyEvent.EffectFailed -> ofLoad(step, event)
        }
    }

    /**
     * Whether an eviction may remove this key now: idle, not removed, and, for a pass that chose it as one kind
     * ([absent] not null), still of that kind. With [onlyIfEmpty], only a key that holds nothing. The shared key never.
     * The runner asks this, then claims the family's decrement, then feeds [KeyEvent.Evict] with what it found, all in
     * one drain step (`SNP-14`).
     */
    fun evictable(state: KeyState<T>, absent: Boolean?, onlyIfEmpty: Boolean): Boolean {
        if (config.shared || state.load != null || state.removed) {
            return false
        }
        val entry = state.installed
        if (onlyIfEmpty && entry != null) {
            return false
        }
        return absent == null || (entry != null && (entry.origin == EntryOrigin.ABSENT) == absent)
    }

    private fun requested(step: KeyStepBuilder<T>, event: KeyEvent.Requested) {
        val waiter = event.waiter
        if (step.state.removed) {
            // Never null: a request goes to the key's current runner; a pass or a revalidation skips the key.
            val skips = event.ask is Ask.ReloadIfHeld || (event.ask is Ask.Load && event.ask.pass != null)
            waiter?.let { step.emit(if (skips) KeyEffect.Skip(it) else KeyEffect.Redispatch(event)) }
            return
        }
        when (val ask = event.ask) {
            Ask.Request -> request(step, waiter)
            Ask.Usable -> usable(step, waiter)
            is Ask.Load -> load(step, ask.force, ask.pass, waiter)
            is Ask.Reload -> reload(step, ask.force, waiter)
            is Ask.ReloadIfHeld -> reload(step, ask.force, waiter)
        }
    }

    /** `forRequest`: what is installed, or its own view of the load; after close, refused as today's view is aborted. */
    private fun request(step: KeyStepBuilder<T>, waiter: Waiter?) {
        waiter ?: return
        val installed = step.state.installed
        when {
            installed != null -> step.emit(KeyEffect.Serve(waiter, installed, hit = true))
            step.moment.closed -> step.emit(KeyEffect.Refuse(waiter, closedError()))
            else -> step.emit(KeyEffect.Hang(waiter, joinOrStart(step, force = false, pass = null)))
        }
    }

    /** `usable`: the installed entry if it has not failed, otherwise a load. */
    private fun usable(step: KeyStepBuilder<T>, waiter: Waiter?) {
        val installed = step.state.installed
        if (installed != null && installed.state !is DocumentState.Failed) {
            waiter?.let { step.emit(KeyEffect.Serve(it, installed)) }
            return
        }
        load(step, force = false, pass = null, waiter = waiter)
    }

    /**
     * `load`: the load in flight, or a new one; after close, a failure. A [pass] does not wait for a load that has been
     * in flight longer than the store read timeout, whoever started it, and skips the key: the store's answer is in by
     * then, so the load is held by a compilation, and confirms the document when it ends (`SNP-6`).
     */
    private fun load(step: KeyStepBuilder<T>, force: Boolean, pass: Pass?, waiter: Waiter?) {
        if (step.moment.closed) {
            waiter?.let { step.emit(KeyEffect.Serve(it, failedEntry(config.key, closedError(), step.moment.instant))) }
            return
        }
        val inFlight = step.state.load
        if (inFlight != null && pass != null && step.moment.nanos - inFlight.startedNanos > config.storeReadTimeout.toNanos()) {
            waiter?.let { step.emit(KeyEffect.Skip(it)) }
            return
        }
        val handle = joinOrStart(step, force, pass)
        waiter?.let { step.emit(KeyEffect.Hang(it, handle)) }
    }

    /** `reload`: a load that starts after this event, even if one is in flight, since a change may postdate its read. */
    private fun reload(step: KeyStepBuilder<T>, force: Boolean, waiter: Waiter?) {
        val state = step.state
        if (state.load == null) {
            load(step, force, pass = null, waiter = waiter)
            return
        }
        val next = state.next ?: step.handle()
        step.state = step.state.copy(again = true, againForced = state.againForced || force, next = next)
        waiter?.let { step.emit(KeyEffect.Hang(it, next)) }
    }

    /** `onChanged`: reload if this key is installed or loading; otherwise there is nothing to refresh. */
    private fun changed(step: KeyStepBuilder<T>) {
        val state = step.state
        when {
            state.removed -> Unit
            state.load != null -> step.state = state.copy(again = true)
            state.installed != null -> load(step, force = false, pass = null, waiter = null)
        }
    }

    /**
     * `markRecompile`, after the epoch moved: a load in flight is followed by another, which starts in the new epoch.
     * True when this key is to be loaded again (loading, or idle with a compiled entry): the caller reloads it.
     */
    private fun recompile(step: KeyStepBuilder<T>): Boolean {
        val state = step.state
        return when {
            state.removed -> false
            state.load != null -> true.also { step.state = state.copy(again = true) }
            else -> state.installed?.origin?.compiled ?: false
        }
    }

    /** `removeIfIdle`: removes exactly when the runner claimed the removal, or when no kind was named and it may. */
    private fun evict(step: KeyStepBuilder<T>, event: KeyEvent.Evict) {
        val removes = when (event.claim) {
            Claim.CLAIMED -> true.also {
                check(evictable(step.state, absent = null, event.onlyIfEmpty)) {
                    "An eviction of [${config.key}] was claimed for a key that cannot be evicted."
                }
            }

            Claim.UNCLAIMED -> evictable(step.state, absent = null, event.onlyIfEmpty)
            Claim.NOT_EVICTABLE, Claim.AT_BOUND -> false
        }
        if (removes) {
            step.state = step.state.copy(removed = true)
            step.emit(KeyEffect.Drop(claimed = event.claim == Claim.CLAIMED))
        }
        step.emit(KeyEffect.Reply(event.reply, removes))
    }

    /**
     * `endLoad`, closing: what is installed stays, as for a read that failed, and is what the load's waiters get,
     * unless a recompile has outdated it.
     */
    private fun close(step: KeyStepBuilder<T>) {
        val load = step.state.load ?: return
        val outdated = load.recompiles || load.epoch < step.moment.epoch
        val entry = load.known.takeUnless { outdated } ?: failedEntry(config.key, closedError(), step.moment.instant)
        loads.finish(step, load, entry, install = false)
    }

    /** An event about a load: dropped when that load has ended, or when it has moved past what the event is about. */
    private fun ofLoad(step: KeyStepBuilder<T>, event: KeyEvent<T>) {
        val load = step.state.load?.takeIf { it.id == event.loadId }
        if (load == null) {
            if (event is KeyEvent.EffectFailed) {
                step.emit(
                    KeyEffect.Log(
                        KeyEffect.Level.ERROR,
                        "Snapshot document [${config.key}] failed after its load finished.",
                        event.cause
                    )
                )
            }
            return
        }
        when (val phase = load.phase) {
            LoadPhase.Reading -> whileReading(step, load, event)
            is LoadPhase.Compiling -> whileCompiling(step, load, phase.read, event)
        }
    }

    /** What the store answered; an event about compiling is not about this load yet, and an effect failed ends it. */
    private fun whileReading(step: KeyStepBuilder<T>, load: LoadState<T>, event: KeyEvent<T>) {
        when (event) {
            is KeyEvent.ReadDone -> loads.onRead(step, load, event.read)
            is KeyEvent.ReadFailed -> loads.onReadFailed(step, load, event.cause)
            is KeyEvent.ReadTimedOut -> loads.onReadFailed(step, load, readTimeout())
            is KeyEvent.EffectFailed -> loads.onTransientFailure(step, load, event.cause)
            else -> Unit
        }
    }

    /** What the compile pool did; a read's answer or timeout is about a read this load has moved past. */
    private fun whileCompiling(
        step: KeyStepBuilder<T>,
        load: LoadState<T>,
        read: SnapshotRead.Present,
        event: KeyEvent<T>
    ) {
        when (event) {
            is KeyEvent.Compiled -> loads.onCompiled(step, load, read, event)
            is KeyEvent.CompileCrashed -> loads.onCompileCrashed(step, load, read, event.failure)
            is KeyEvent.CompileRejected -> {
                val cause = SnapshotUnavailableException(config.key, "the compile queue is full", event.cause)
                loads.onTransientFailure(step, load, cause, rejected = true)
            }

            is KeyEvent.EffectFailed -> loads.onTransientFailure(step, load, event.cause)
            else -> Unit
        }
    }

    private fun joinOrStart(step: KeyStepBuilder<T>, force: Boolean, pass: Pass?): KeyHandle<T> {
        step.state.load?.let { return it.handle }
        val load = loads.newLoad(step, force, pass, handle = null)
        step.state = step.state.copy(again = false)
        loads.startRead(step, load)
        return load.handle
    }

    private fun readTimeout() = TimeoutException("The store did not answer within ${config.storeReadTimeout}.")

    private fun closedError() = IllegalStateException("SnapshotSource is closed.")
}
