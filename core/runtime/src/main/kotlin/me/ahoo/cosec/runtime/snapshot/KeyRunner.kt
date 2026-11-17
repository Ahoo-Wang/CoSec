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
 * One key run by [KeyTransitions] (RFC 0013 B2b, step 2b): each [KeySlot] call becomes an event offered to its
 * [KeyDrain], whose effects [KeyEffects] executes. `peek`'s members are [KeySlot]'s, and a request's hit is answered
 * outside the queue, as [Slot] answers it. The constructor starts nothing: no timer, no read, nothing offered.
 * Dependencies go one way: this → [KeyDrain] → [KeyEffects], which reach back only through [KeyOwner], which this
 * implements.
 */
internal class KeyRunner<T : Any>(
    key: SnapshotKey,
    compiler: SnapshotCompiler<T>,
    private val context: SlotContext,
    /** The events past their own a caller's thread drains; provisional, until step 2b-ii's measurement sets it. */
    k: Int = PROVISIONAL_K,
    /** Tests only: where a test interleaves with this key's drain. */
    hooks: KeyDrain.Hooks = KeyDrain.Hooks.NONE,
) : KeySlot<T>(key, context.ticker), KeyOwner<T> {
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

    override fun request(): CompletableFuture<SnapshotEntry<T>> {
        if (!removed) {
            installed?.let {
                touch()
                context.metrics.onLoad(key.kind, LoadOutcome.HIT)
                return CompletableFuture.completedFuture(it)
            }
        }
        val waiter = KeyWaiter.Request<T>(key, context).arm()
        if (!waiter.isDone) offer(KeyEvent.Requested(Ask.Request, waiter))
        return waiter
    }

    override fun usable(): CompletableFuture<SnapshotEntry<T>> {
        val entry = installed
        if (!removed && entry != null && entry.state !is DocumentState.Failed) {
            return CompletableFuture.completedFuture(entry)
        }
        return KeyWaiter.Entry<T>().also { offer(KeyEvent.Requested(Ask.Usable, it)) }
    }

    override fun reload(force: Boolean): CompletableFuture<SnapshotEntry<T>> =
        KeyWaiter.Entry<T>().also { offer(KeyEvent.Requested(Ask.Reload(force), it)) }

    override fun reloadIfHeld(force: Boolean): CompletableFuture<Reloaded<T>> =
        KeyWaiter.Held<T>().also { offer(KeyEvent.Requested(Ask.ReloadIfHeld(force), it)) }

    override fun load(pass: Pass): CompletableFuture<Reloaded<T>> =
        KeyWaiter.Held<T>().also { offer(KeyEvent.Requested(Ask.Load(force = false, pass = pass), it)) }

    override fun changed() = offer(KeyEvent.Changed)

    override fun markRecompile(): CompletableFuture<Boolean> = CompletableFuture<Boolean>().also {
        offer(KeyEvent.Recompile(it))
    }

    override fun evict(onlyIfEmpty: Boolean, claimedAbsent: Boolean?): CompletableFuture<Eviction> =
        offerEviction(onlyIfEmpty, claimedAbsent)

    override fun close() = drain.close()

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
        context.families.removed(key)
    }

    /** An offer to the key's current runner, not a call chain. */
    @Suppress("UNCHECKED_CAST")
    override fun redispatched(event: KeyEvent.Requested) {
        val current = context.current(key)
        check(current is KeyRunner<*>) { "A runner's request went to [$current], not a runner." }
        (current as KeyRunner<T>).offer(event)
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

    internal companion object {
        /** Provisional: step 2b-ii's measurement (K ∈ 0, 4, 16, 64) sets it. */
        const val PROVISIONAL_K = 16
    }
}
