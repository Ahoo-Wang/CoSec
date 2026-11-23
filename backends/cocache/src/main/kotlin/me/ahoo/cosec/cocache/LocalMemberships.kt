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

package me.ahoo.cosec.cocache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The memberships a [MembershipLoader] answered, held by this process only (DR1-D-2): never written to or read from
 * Redis, so no holder of a Redis credential can answer for the loader. Each entry lives [ttl] from when its load
 * answered; at most [maxEntries] are held, the membership share of the heap counted at [ENTRY_BYTES] an entry.
 *
 * A fresh entry answers on the caller's thread ([peek]). Concurrent misses of one key share one load ([get]); a load that
 * fails is never kept, so an outage never reads as an answer. [invalidate] drops an entry, one still loading included:
 * its load answers the requests that wait for it, and is not kept.
 */
@InternalCoSecApi
class LocalMemberships(
    private val ttl: Duration,
    private val maxEntries: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "The membership lifetime must be positive, not $ttl." }
        require(maxEntries > 0) { "The membership cache holds at least one entry, not $maxEntries." }
    }

    private class Entry(val loading: CompletableFuture<Memberships>) {
        /** When it stops answering, in [nanoTime]; set before [loading] completes. */
        @Volatile
        var expiresAt: Long = Long.MAX_VALUE
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** How many entries are held now. */
    val size: Int get() = entries.size

    /** [key]'s memberships when a fresh entry holds them, on the caller's thread; null otherwise. */
    fun peek(key: String): Memberships? {
        val entry = entries[key] ?: return null
        val loading = entry.loading
        if (!loading.isDone || loading.isCompletedExceptionally) return null
        if (nanoTime() - entry.expiresAt >= 0) {
            entries.remove(key, entry)
            return null
        }
        return loading.getNow(null)
    }

    /**
     * [key]'s memberships: a fresh entry's, the load of [key] in flight, or a load [load] starts now and this cache keeps
     * once it answers.
     */
    fun get(key: String, load: () -> CompletableFuture<Memberships>): CompletableFuture<Memberships> {
        while (true) {
            val existing = entries[key]
            if (existing != null && usable(existing)) return existing.loading
            val fresh = Entry(CompletableFuture())
            val won = if (existing == null) {
                entries.putIfAbsent(key, fresh) == null
            } else {
                entries.replace(key, existing, fresh)
            }
            if (!won) continue
            bound(key)
            start(key, fresh, load)
            return fresh.loading
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun start(key: String, entry: Entry, load: () -> CompletableFuture<Memberships>) {
        val started = try {
            load()
        } catch (error: Throwable) {
            CompletableFuture.failedFuture(error)
        }
        started.whenComplete { value, error ->
            if (error != null) {
                entries.remove(key, entry)
                entry.loading.completeExceptionally(error)
            } else {
                entry.expiresAt = nanoTime() + ttl.toNanos()
                entry.loading.complete(value)
            }
        }
    }

    /** In flight, or answered and not expired. */
    private fun usable(entry: Entry): Boolean {
        val loading = entry.loading
        if (!loading.isDone) return true
        return !loading.isCompletedExceptionally && nanoTime() - entry.expiresAt < 0
    }

    /**
     * Past [maxEntries]: the expired go first, then any but [added], until it holds [maxEntries]. A dropped entry loads
     * again on its next request.
     */
    private fun bound(added: String) {
        if (entries.size <= maxEntries) return
        val now = nanoTime()
        entries.entries.removeIf { (_, entry) -> entry.loading.isDone && now - entry.expiresAt >= 0 }
        val over = entries.keys.iterator()
        while (entries.size > maxEntries && over.hasNext()) {
            if (over.next() != added) over.remove()
        }
    }

    /** Drops [key]'s entry; the next request loads it. */
    fun invalidate(key: String) {
        entries.remove(key)
    }

    /** Drops every entry. */
    fun clear() {
        entries.clear()
    }

    @InternalCoSecApi
    companion object {
        /** How long an entry lives when nothing else is said: the starter's default. */
        val DEFAULT_TTL: Duration = Duration.ofMinutes(1)

        /**
         * What one entry retains, measured (`LocalMembershipsSizeTest`: 2,293 bytes) for a principal of ten roles in two
         * spaces and five attached policies: the membership share of the heap is counted in entries of this size
         * (`STA-12`).
         */
        const val ENTRY_BYTES: Long = 3_072
    }
}
