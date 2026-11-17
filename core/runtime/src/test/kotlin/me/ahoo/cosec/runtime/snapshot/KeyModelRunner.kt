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
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Claim
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The key-level stand-in for the runner (RFC 0013 B2b, step 1), on [SlotModelThreads] and [SlotModelStore]. As dumb as
 * it can be: events are taken in the order they were offered, each effect is executed as the page says, in the order
 * [KeyTransitions.next] returned it, and an event an effect raises is queued behind what is there, never recursed into.
 * It drops, merges and reorders nothing; every such decision is `next`'s.
 *
 * Its one rule of its own is the page's (§4b): an eviction is taken from the queue as a request, and only then asked
 * [KeyTransitions.evictable], claimed, and fed to `next` as an [KeyEvent.Evict] carrying what was found.
 */
internal class KeyModelRunner(
    private val world: KeyModelWorld,
    private val transitions: KeyTransitions<String>,
) {
    var state: KeyState<String> = transitions.initial()
        private set

    /** What `installed` publishes: written by the install effect alone. */
    var installed: SnapshotEntry<String>? = null
        private set

    /** Set by the drop effect: the key's map no longer holds this runner. */
    var dropped = false
        private set

    private val queue = ArrayDeque<Any>()
    private var draining = false
    private val readTimeouts = HashMap<Long, ScheduledFuture<*>>()

    /** An eviction as offered: claimed when it is taken (§4b), by [claim] when the key is evictable. */
    class EvictRequest(
        val onlyIfEmpty: Boolean,
        val absent: Boolean?,
        val claim: () -> Boolean,
        val reply: CompletableFuture<Boolean>
    )

    fun offer(event: KeyEvent<String>) = enqueue(event)

    fun offerEvict(request: EvictRequest) = enqueue(request)

    private fun enqueue(item: Any) {
        queue.addLast(item)
        if (draining) return
        draining = true
        try {
            while (true) {
                val next = queue.removeFirstOrNull() ?: break
                take(if (next is EvictRequest) claimed(next) else next as KeyEvent<String>)
            }
        } finally {
            draining = false
        }
    }

    private fun claimed(request: EvictRequest): KeyEvent.Evict {
        val claim = when {
            request.absent == null -> Claim.UNCLAIMED
            !transitions.evictable(state, request.absent, request.onlyIfEmpty) -> Claim.NOT_EVICTABLE
            request.claim() -> Claim.CLAIMED
            else -> Claim.AT_BOUND
        }
        return KeyEvent.Evict(request.onlyIfEmpty, claim, request.reply)
    }

    private fun take(event: KeyEvent<String>) {
        val before = state
        val step = transitions.next(before, event, world.moment())
        world.checkStep(before, event, step)
        state = step.state
        step.effects.forEach(::execute)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun execute(effect: KeyEffect<String>) {
        when (effect) {
            is KeyEffect.StartPassRead -> effect.ticket.read = effect.ticket.pass.startRead()
            is KeyEffect.EndPassRead -> effect.ticket.read?.end()
            is KeyEffect.StartRead -> startRead(effect)
            is KeyEffect.CancelReadTimeout -> readTimeouts.remove(effect.loadId)?.cancel(false)
            is KeyEffect.Compile -> compile(effect)
            is KeyEffect.RecordSeen -> world.seen = if (effect.ifAbsent) world.seen ?: effect.seen else effect.seen
            is KeyEffect.Confirm -> effect.entry.confirm(effect.at)
            is KeyEffect.Install -> install(effect.entry)
            is KeyEffect.Answer -> effect.handle.future.complete(effect.entry)
            is KeyEffect.Hang -> effect.handle.future.whenComplete { entry, _ ->
                world.answer(
                    effect.waiter,
                    entry,
                    effect.handle
                )
            }
            is KeyEffect.Serve -> world.answer(effect.waiter, effect.entry, null)
            is KeyEffect.Refuse -> world.refuse(effect.waiter)
            is KeyEffect.Skip -> world.skip(effect.waiter)
            is KeyEffect.Redispatch -> world.redispatch(effect.event)
            is KeyEffect.ScheduleRetry -> world.threads.scheduler.schedule(
                Runnable { offer(KeyEvent.RetryDue()) },
                effect.delay.toNanos(),
                TimeUnit.NANOSECONDS
            )
            KeyEffect.DropIfEmpty -> world.threads.housekeeping.execute { world.dropIfEmpty(this) }
            KeyEffect.EvictBeyond -> world.threads.housekeeping.execute { world.evictBeyond(this) }
            is KeyEffect.Drop -> dropped = true
            is KeyEffect.Reply -> effect.reply.complete(effect.value)
            is KeyEffect.Log, is KeyEffect.Report -> world.counts.merge(effect.javaClass.simpleName, 1, Int::plus)
        }
    }

    private fun install(entry: SnapshotEntry<String>) {
        installed = entry
        world.installed(entry)
    }

    /** `StartRead`: the store call only starts the read; its answer is offered back, behind whatever is queued. */
    private fun startRead(effect: KeyEffect.StartRead) {
        val id = effect.loadId
        val reading = if (effect.fromLocalCopy) {
            world.store.read(
                world.key
            )
        } else {
            world.store.readIfChanged(world.key, effect.digest)
        }
        world.readStarted(this, id)
        if (!reading.isDone) {
            readTimeouts[id] = world.threads.scheduler.schedule(
                Runnable { offer(KeyEvent.ReadTimedOut(id)) },
                world.storeReadTimeout.toNanos(),
                TimeUnit.NANOSECONDS,
            )
        }
        reading.whenComplete { read: SnapshotRead?, error: Throwable? ->
            offer(if (error != null) KeyEvent.ReadFailed(id, error) else KeyEvent.ReadDone(id, read!!))
        }
    }

    /** `Compile`: on the compile pool only; a refusal is offered back as such. */
    private fun compile(effect: KeyEffect.Compile) {
        val id = effect.loadId
        try {
            world.threads.compileExecutor.execute {
                val compiled = world.compile(effect.read)
                when (compiled) {
                    is VirtualMachineError -> {
                        offer(
                            KeyEvent.CompileCrashed(
                                id,
                                SnapshotUnavailableException(world.key, "does not compile", compiled)
                            )
                        )
                        throw compiled
                    }

                    is Throwable -> offer(
                        KeyEvent.Compiled(
                            id,
                            DocumentState.Failed(SnapshotUnavailableException(world.key, "does not compile", compiled)),
                            1
                        )
                    )

                    else -> offer(KeyEvent.Compiled(id, DocumentState.Present(compiled as String), 1))
                }
            }
        } catch (rejected: RejectedExecutionException) {
            offer(KeyEvent.CompileRejected(id, rejected))
        }
    }

    /** What the world must provide a runner: the store, the threads, `families.seen`, and where answers are recorded. */
    interface KeyModelWorld {
        val key: me.ahoo.cosec.spi.snapshot.SnapshotKey
        val store: SlotModelStore
        val threads: SlotModelThreads
        val storeReadTimeout: java.time.Duration
        val counts: MutableMap<String, Int>
        var seen: Seen?

        fun moment(): KeyMoment

        fun checkStep(before: KeyState<String>, event: KeyEvent<String>, step: KeyStep<String>)

        /** [runner]'s load [loadId] just started the store's latest read. */
        fun readStarted(runner: KeyModelRunner, loadId: Long)

        /** The compiler: a compiled document, or the [Throwable] compiling it threw. */
        fun compile(read: SnapshotRead.Present): Any

        fun installed(entry: SnapshotEntry<String>)

        fun answer(waiter: KeyEvent.Waiter, entry: SnapshotEntry<String>?, handle: KeyHandle<String>?)

        fun refuse(waiter: KeyEvent.Waiter)

        fun skip(waiter: KeyEvent.Waiter)

        fun redispatch(event: KeyEvent.Requested)

        fun dropIfEmpty(runner: KeyModelRunner)

        fun evictBeyond(runner: KeyModelRunner)
    }
}
