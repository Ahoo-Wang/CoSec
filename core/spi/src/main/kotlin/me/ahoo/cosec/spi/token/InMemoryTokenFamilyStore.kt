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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong

/**
 * A [TokenFamilyStore] in this JVM's memory, with the same atomicity: for tests and a single instance. A family past
 * its end reads as absent, as on Redis, and is released: every call frees up to [RELEASED_PER_CALL] families whose end
 * has passed, oldest end first, so a long-running instance does not keep every session it ever had. There is no
 * background thread, and a family that is replaced is never freed by a sweep that began before: a sweep removes an entry
 * only if, at the moment it does, the entry itself has ended.
 *
 * Its end is the shorter of what is left of the family's lifetime and [refreshValidity], from each write, so a session
 * nobody refreshes is released with its last refresh token; without a [refreshValidity], its lifetime. It keeps the
 * [TokenFamilyStore.SESSIONS_PER_PRINCIPAL] sessions of a principal in a tenant that began last, and deletes the one
 * that began earliest when one more is created.
 *
 * Every entry has exactly one end in the sweep's order: when a write moves it, or a pushed-out family is deleted, its
 * old end is taken out, so the order holds no more ends than the store holds families.
 */
class InMemoryTokenFamilyStore(
    private val now: () -> Instant,
    /** The refresh token validity (`cosec.jwt.token-validity.refresh`), or null to keep each family to its end. */
    private val refreshValidity: Duration?,
) : TokenFamilyStore {
    @JvmOverloads
    constructor(now: () -> Instant = Instant::now) : this(now, null)

    init {
        require(refreshValidity == null || refreshValidity.isPositive) {
            "A refresh token validity is positive, not $refreshValidity."
        }
    }

    private class Entry(val family: TokenFamily, val version: String, val endsAt: Instant)

    /** When a family ends, and its id: the order the sweep takes them in. */
    private data class Ending(val at: Instant, val id: String)

    private val families = ConcurrentHashMap<String, Entry>()
    private val endings = ConcurrentSkipListSet<Ending>(compareBy<Ending>({ it.at }, { it.id }))
    private val versions = AtomicLong()
    private val sessions = InMemorySessionIndex(now)

    /** How many families the store holds, ended ones not yet released included. */
    internal val size: Int get() = families.size

    /** How many ends the sweep's order holds: one per family. */
    internal val endCount: Int get() = endings.size

    private fun live(id: String): Entry? = families[id]?.takeIf { it.endsAt.isAfter(now()) }

    /** Frees up to [RELEASED_PER_CALL] ended families, and [also] if it has ended. */
    private fun release(also: String? = null) {
        val at = now()
        also?.let { drop(it, at) }
        val iterator = endings.iterator()
        var freed = 0
        while (freed < RELEASED_PER_CALL && iterator.hasNext()) {
            val ending = iterator.next()
            if (ending.at.isAfter(at)) return
            endings.remove(ending)
            drop(ending.id, at)
            freed++
        }
    }

    /** Removes [id] if its entry has ended at [at], with its end; an entry replaced since, by one that has not, stays. */
    private fun drop(id: String, at: Instant) {
        families.computeIfPresent(id) { _, entry ->
            if (entry.endsAt.isAfter(at)) {
                entry
            } else {
                endings.remove(Ending(entry.endsAt, id))
                null
            }
        }
    }

    /** Takes [entry]'s end out of the sweep's order: it is being replaced or deleted. */
    private fun forget(id: String, entry: Entry) {
        endings.remove(Ending(entry.endsAt, id))
    }

    /** How long a write keeps [family]: what is left of its lifetime, or less, the refresh token validity. */
    private fun endOf(family: TokenFamily, at: Instant): Instant {
        val byRefresh = refreshValidity?.let { at.plus(it) } ?: return family.expiresAt
        return minOf(family.expiresAt, byRefresh)
    }

    /** The entry for [family], its end indexed before it is visible, so a sweep never finds one it cannot see. */
    private fun entryOf(family: TokenFamily): Entry {
        val endsAt = endOf(family, now())
        endings.add(Ending(endsAt, family.id))
        return Entry(family, versions.incrementAndGet().toString(), endsAt)
    }

    override suspend fun create(family: TokenFamily): Boolean {
        release()
        val at = now()
        // Counted before it is written: a record the index does not count never exists.
        val pushedOut = sessions.admit(
            family,
            at,
            Duration.between(at, family.expiresAt).coerceAtLeast(Duration.ZERO),
            TokenFamilyStore.SESSIONS_PER_PRINCIPAL,
        )
        pushedOut.forEach { id -> families.remove(id)?.let { forget(id, it) } }
        var created = false
        families.compute(family.id) { _, existing ->
            if (existing != null && existing.endsAt.isAfter(now())) {
                existing
            } else {
                existing?.let { forget(family.id, it) }
                created = true
                entryOf(family)
            }
        }
        return created
    }

    override suspend fun read(id: String): TokenFamilyStore.Read? {
        release(also = id)
        return live(id)?.let { TokenFamilyStore.Read(it.family, it.version) }
    }

    override suspend fun replace(version: String, next: TokenFamily): Boolean {
        release()
        var replaced = false
        families.computeIfPresent(next.id) { _, existing ->
            if (existing.version == version && existing.endsAt.isAfter(now())) {
                forget(next.id, existing)
                replaced = true
                entryOf(next)
            } else {
                existing
            }
        }
        return replaced
    }

    private companion object {
        /** The bound on the work one call adds: the sweep is incremental, never a pass over the store. */
        const val RELEASED_PER_CALL = 16
    }
}
