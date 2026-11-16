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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/**
 * The passes of a [SnapshotSource] over its installed slots (RFC 0013 B2a), periodic or requested ([reloadAll]).
 *
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
internal class Passes(
    private val shared: Slot<*>,
    private val families: SnapshotFamilies<*>,
    private val context: SlotContext,
    private val lagReports: LagReports,
) {
    // Guarded by this.
    private var current: Pass? = null
    private var owed: CompletableFuture<Void>? = null

    fun tick(): CompletableFuture<Void> {
        val now = context.nanoTime.get().asLong
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
            context.threads.offTimer {
                log.warn {
                    "A snapshot revalidation pass finished no document for ${context.options.storeReadTimeout} and has no " +
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

    fun installedSlots(): List<Slot<*>> = buildList {
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
    fun reloadAll(
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
        repeat(minOf(context.options.reloadConcurrency, slots.size)) { lane() }
        return done
    }

    /**
     * One periodic tick: queue a lag report, then revalidate, unless a pass is in flight ([tick]). The future
     * completes when a pass that started after this call has ended.
     */
    fun periodic(): CompletableFuture<Void> {
        lagReports.queue()
        return tick()
    }

    /** Schedules [periodic] every [SnapshotOptions.revalidateInterval]; the source does, once, while it is built. */
    fun schedulePeriodic(): ScheduledFuture<*> {
        val millis = context.options.revalidateInterval.toMillis()
        return context.threads.scheduler.scheduleWithFixedDelay(::periodicTick, millis, millis, TimeUnit.MILLISECONDS)
    }

    /** Never lets anything escape: a throwing task would silently cancel the periodic schedule. */
    @Suppress("TooGenericExceptionCaught")
    private fun periodicTick() {
        try {
            periodic()
        } catch (error: Throwable) {
            context.threads.offTimer { log.error(error) { "Snapshot revalidation pass failed." } }
        }
    }
}
