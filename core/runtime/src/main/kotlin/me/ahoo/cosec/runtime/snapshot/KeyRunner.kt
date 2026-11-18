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
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.CompletableFuture

/**
 * One key of a [SnapshotSource], as its callers see it, run by [KeyTransitions] (RFC 0013 B2b): each call becomes an
 * event offered to its [KeyDrain], whose effects [KeyEffects] executes. Everything is asynchronous: an answer is a
 * future, and no request is ever null. `peek`'s members are [PeekedKey]'s, and a request's hit is answered outside the
 * queue, on the caller's thread. The constructor starts nothing: no timer, no read, nothing offered.
 * Dependencies go one way: this → [KeyDrain] → [KeyEffects], which reach back only through [KeyOwner], which this
 * implements.
 */
internal class KeyRunner<T : Any>(
    key: SnapshotKey,
    compiler: SnapshotCompiler<T>,
    private val context: KeyContext,
    /** The events past their own a caller's thread drains. */
    k: Int = K,
    /** Tests only: where a test interleaves with this key's drain. */
    hooks: KeyDrain.Hooks = KeyDrain.Hooks.NONE,
) : PeekedKey<T>(key, context.ticker), KeyOwner<T> {
    private val family = context.families.firstOrNull { it.spec.kind == key.kind }

    @Volatile
    private var idleHint = true

    private val drain: KeyDrain<T>

    init {
        // The source's transitions, shared by its runners: each step carries this key (RFC 0013 B2b step 3).
        val transitions = context.transitions<T>(shared = key == context.sharedKey)
        drain = KeyDrain(key, transitions, KeyEffects(key, compiler, context, this), context, k, this, hooks)
    }

    override fun idle(): Boolean = idleHint

    override val draining: Boolean get() = !drain.quiet()

    /** What one request waits for: the installed entry, or its own view of the load, bounded by the cold load timeout. */
    @Suppress("TooGenericExceptionCaught")
    fun request(): CompletableFuture<SnapshotEntry<T>> {
        if (!removed) {
            installed?.let {
                touch()
                context.metrics.onLoad(key.kind, LoadOutcome.HIT)
                return CompletableFuture.completedFuture(it)
            }
        }
        val waiter = KeyWaiter.Request<T>(key, context).register()
        if (!waiter.isDone) {
            try {
                offer(KeyEvent.Requested(Ask.Request, waiter))
            } catch (error: Throwable) {
                // Registered and never timed: answered here, or nobody would answer it before close.
                waiter.fail(error)
                throw error
            }
            waiter.arm()
        }
        return waiter
    }

    /** The installed entry if it has not failed, otherwise the load in flight or a new one. */
    fun usable(): CompletableFuture<SnapshotEntry<T>> {
        val entry = installed
        if (!removed && entry != null && entry.state !is DocumentState.Failed) {
            return CompletableFuture.completedFuture(entry)
        }
        return KeyWaiter.Entry<T>().also { offer(KeyEvent.Requested(Ask.Usable, it)) }
    }

    /** A load that starts after this call, in full when [force] (`SNP-13`); a removed key's goes to its current runner. */
    fun reload(force: Boolean): CompletableFuture<SnapshotEntry<T>> =
        KeyWaiter.Entry<T>().also { offer(KeyEvent.Requested(Ask.Reload(force), it)) }

    /** As [reload] for a key still held; a removed one is [Reloaded.Skipped]: revalidation, reset, recompile, retries. */
    fun reloadIfHeld(force: Boolean): CompletableFuture<Reloaded<T>> =
        KeyWaiter.Held<T>().also { offer(KeyEvent.Requested(Ask.ReloadIfHeld(force), it)) }

    /**
     * A load that started after [pass] did, counted as a read of [pass] when the pass starts it: the load in flight if
     * it did, otherwise a new one, or the one after the load in flight (`SNP-6`). Skipped for a removed key, and for a
     * load in flight longer than the store read timeout, which the pass does not wait for.
     */
    fun load(pass: Pass): CompletableFuture<Reloaded<T>> =
        KeyWaiter.Held<T>().also { offer(KeyEvent.Requested(Ask.Load(force = false, pass = pass), it)) }

    /** A change notification: reload if this key is installed or loading. */
    fun changed() = offer(KeyEvent.Changed)

    /** After the compile epoch moved: whether this key is to be loaded again (loading, or idle with a compiled entry). */
    fun markRecompile(): CompletableFuture<Boolean> = CompletableFuture<Boolean>().also {
        offer(KeyEvent.Recompile(it))
    }

    /**
     * Removes this key if it is idle and, with [onlyIfEmpty], holds nothing. An eviction names the kind it chose the key
     * for ([claimedAbsent]): refused when the key is of the other kind by now, and at the bound when the family's claim
     * fails (`SNP-14`).
     */
    fun evict(onlyIfEmpty: Boolean, claimedAbsent: Boolean?): CompletableFuture<Eviction> =
        offerEviction(onlyIfEmpty, claimedAbsent)

    /** The source is closing: the load in flight ends, answering its waiters with what is installed or a failure. */
    fun close() = drain.close()

    override fun offer(event: KeyEvent<T>) = drain.offer(event)

    private fun offerEviction(onlyIfEmpty: Boolean, claimedAbsent: Boolean?): CompletableFuture<Eviction> =
        CompletableFuture<Eviction>().also { drain.offer(KeyDrain.EvictRequest(onlyIfEmpty, claimedAbsent, it)) }

    override fun published(previous: SnapshotEntry<T>?, entry: SnapshotEntry<T>) {
        family?.let { context.families.move(it, this, previous, entry) }
        installed = entry
    }

    override fun dropped(claimed: Boolean) {
        val family = family ?: return
        family.drop(family.spec.idOf(key), this)
        if (!claimed) context.families.move(family, this, installed, null)
        context.families.removed(key, installed)
    }

    /** An offer to the key's current runner, not a call chain. */
    @Suppress("UNCHECKED_CAST")
    override fun redispatched(event: KeyEvent.Requested) {
        (context.current(key) as KeyRunner<T>).offer(event)
    }

    override fun dropIfEmpty() {
        offerEviction(true, null)
    }

    override fun claim(absent: Boolean): Boolean = family?.claim(this, absent) ?: false

    override fun giveBack(absent: Boolean) {
        family?.giveBack(this, absent)
    }

    override fun hinted(removed: Boolean, idle: Boolean) {
        if (removed) this.removed = true
        idleHint = idle
    }

    /** Tests only (RFC 0013 B2b): the K and the hooks each key's runner is made with; the defaults in production. */
    class Settings(val k: Int = K, val hooksOf: (SnapshotKey) -> KeyDrain.Hooks = { KeyDrain.Hooks.NONE }) {
        fun <T : Any> create(key: SnapshotKey, compiler: SnapshotCompiler<T>, context: KeyContext): KeyRunner<T> =
            KeyRunner(key, compiler, context, k, hooksOf(key))
    }

    internal companion object {
        /**
         * The events past its own a caller's thread drains before it hands the rest off (RFC 0013 B2b §3). Set by step
         * 2b-ii's measurement, jmh-ab run 44049110176 (`KeyBurstBenchmark`, a burst of 10,000 cold requests on one key
         * from 8 threads, 4 runs per K): the smallest K that is not worse. Below it a second key's cold load waits longer
         * behind the hand-offs (k0's p99 about 11 % over k4…k64); above it the offering caller's p99 rises (k16 about
         * 30 % over k4), with nothing better on the other side.
         */
        const val K = 4
    }
}
