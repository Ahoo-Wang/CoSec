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
 * Dependencies go one way: this → [KeyDrain] → [KeyEffects], which reach back only through the functions given here.
 */
internal class KeyRunner<T : Any>(
    key: SnapshotKey,
    compiler: SnapshotCompiler<T>,
    private val context: SlotContext,
    /** The events past their own a caller's thread drains; provisional, until step 2b-ii's measurement sets it. */
    k: Int = PROVISIONAL_K,
    /** Tests only: where a test interleaves with this key's drain. */
    hooks: KeyDrain.Hooks = KeyDrain.Hooks.NONE,
) : KeySlot<T>(key, context.ticker) {
    private val family = context.families.firstOrNull { it.spec.kind == key.kind }

    @Volatile
    private var idleHint = true

    private val drain: KeyDrain<T>

    init {
        val options = context.options
        val config = KeyConfig(
            key = key,
            shared = key == context.sharedKey,
            storeReadTimeout = options.storeReadTimeout,
            rejectedReloadRetry = options.rejectedReloadRetry,
            revalidateInterval = options.revalidateInterval,
            localCopyMaxAge = context.store.localCopyMaxAge,
        )
        val transitions = KeyTransitions<T>(config)
        val effects = KeyEffects(key, compiler, context, ::offer, ::published, ::dropped, ::redispatched) {
            offerEviction(true, null)
        }
        drain = KeyDrain(key, transitions, effects, context, k, { absent -> family?.claim(this, absent) ?: false }, ::hinted, hooks)
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

    private fun offer(event: KeyEvent<T>) = drain.offer(event)

    private fun offerEviction(onlyIfEmpty: Boolean, claimedAbsent: Boolean?): CompletableFuture<Eviction> =
        CompletableFuture<Eviction>().also { drain.offer(KeyDrain.EvictRequest(onlyIfEmpty, claimedAbsent, it)) }

    /** `Install`'s one way in: the family's counts move, then the volatile write `peek` reads. */
    private fun published(previous: SnapshotEntry<T>?, entry: SnapshotEntry<T>) {
        family?.let { context.families.move(it, this, previous, entry) }
        installed = entry
    }

    /** `Drop`: the key leaves its family; a removal that was not claimed moves the count itself. */
    private fun dropped(claimed: Boolean) {
        val family = family ?: return
        family.drop(family.spec.idOf(key), this)
        if (!claimed) context.families.move(family, this, installed, null)
        context.families.removed(key)
    }

    /** `Redispatch`: the same event, with the same waiter, to the key's current runner; an offer, not a call chain. */
    @Suppress("UNCHECKED_CAST")
    private fun redispatched(event: KeyEvent.Requested) {
        val current = context.current(key)
        check(current is KeyRunner<*>) { "A runner's request went to [$current], not a runner." }
        (current as KeyRunner<T>).offer(event)
    }

    private fun hinted(removed: Boolean, idle: Boolean) {
        if (removed) this.removed = true
        idleHint = idle
    }

    internal companion object {
        /** Provisional: step 2b-ii's measurement (K ∈ 0, 4, 16, 64) sets it. */
        const val PROVISIONAL_K = 16
    }
}
