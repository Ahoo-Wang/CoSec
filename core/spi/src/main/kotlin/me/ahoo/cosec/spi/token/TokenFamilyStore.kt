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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong

/**
 * What the store keeps of one session (RFC 0009): whose it is, when its user signed in ([authTime]), when it ends for
 * good ([expiresAt], the absolute lifetime), its current [generation], when that generation became current
 * ([rotatedAt], null for the first), and whether it was [revoked]. A revoked family is kept, marked, until [expiresAt],
 * so it is never mistaken for one that expired.
 */
class TokenFamily(
    val id: String,
    val tenant: TenantId,
    val principal: PrincipalId,
    val authTime: Instant,
    val expiresAt: Instant,
    val generation: Long,
    val rotatedAt: Instant?,
    val revoked: Boolean,
) {
    init {
        require(id.isNotEmpty()) { "A token family has an id." }
        require(generation >= 1) { "A token family's generation is positive, not $generation." }
        require(expiresAt.isAfter(authTime)) { "A token family ends after its login, not at $expiresAt." }
    }

    /** This family at the next generation, current since [at]. */
    fun rotated(at: Instant): TokenFamily =
        TokenFamily(id, tenant, principal, authTime, expiresAt, generation + 1, at, revoked)

    /** This family, revoked: no pair of it refreshes any more. */
    fun revoked(): TokenFamily = TokenFamily(id, tenant, principal, authTime, expiresAt, generation, rotatedAt, true)

    private fun fields(): List<Any?> =
        listOf(id, tenant, principal, authTime, expiresAt, generation, rotatedAt, revoked)

    override fun equals(other: Any?): Boolean = other is TokenFamily && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "TokenFamily($id, generation $generation, revoked=$revoked)"
}

/**
 * Where token families are kept (RFC 0009): read and written only when a token is issued, refreshed or revoked, never
 * on a request. Every write is conditional, so of two refreshes of one generation at most one moves the family on,
 * and an implementation honours that across instances (one key, one conditional write). A family lives until its
 * [TokenFamily.expiresAt] and no longer.
 *
 * Failing closed is the caller's: a store that cannot answer, or cannot confirm a write, throws; it never reads a
 * family as absent or a write as done.
 */
@CoSecSpi
interface TokenFamilyStore {
    /** A family as read, with the [version] a [replace] names. */
    class Read(val family: TokenFamily, val version: String)

    /** Records a new [family]; false when one with its id exists. */
    suspend fun create(family: TokenFamily): Boolean

    /** The family [id] names, or null when there is none (never created, or past its end). */
    suspend fun read(id: String): Read?

    /** Replaces the family read at [version] by [next]; false when it changed since. */
    suspend fun replace(version: String, next: TokenFamily): Boolean
}

/**
 * A [TokenFamilyStore] in this JVM's memory, with the same atomicity: for tests and a single instance. A family past
 * its end reads as absent, as on Redis, and is released: every call frees up to [RELEASED_PER_CALL] families whose end
 * has passed, oldest end first, so a long-running instance does not keep every session it ever had. There is no
 * background thread, and a family that is replaced is never freed by a sweep that began before: a sweep removes an entry
 * only if, at the moment it does, the entry itself has ended.
 */
class InMemoryTokenFamilyStore(private val now: () -> Instant = Instant::now) : TokenFamilyStore {
    private class Entry(val family: TokenFamily, val version: String)

    /** When a family ends, and its id: the order the sweep takes them in. */
    private data class Ending(val at: Instant, val id: String)

    private val families = ConcurrentHashMap<String, Entry>()
    private val endings = ConcurrentSkipListSet<Ending>(compareBy<Ending>({ it.at }, { it.id }))
    private val versions = AtomicLong()

    /** How many families the store holds, ended ones not yet released included. */
    internal val size: Int get() = families.size

    private fun live(id: String): Entry? = families[id]?.takeIf { it.family.expiresAt.isAfter(now()) }

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

    /** Removes [id] if its entry has ended at [at]; an entry replaced since, by one that has not, stays. */
    private fun drop(id: String, at: Instant) {
        families.computeIfPresent(id) { _, entry -> if (entry.family.expiresAt.isAfter(at)) entry else null }
    }

    /** The entry for [family], its end indexed before it is visible, so a sweep never finds one it cannot see. */
    private fun entryOf(family: TokenFamily): Entry {
        endings.add(Ending(family.expiresAt, family.id))
        return Entry(family, versions.incrementAndGet().toString())
    }

    override suspend fun create(family: TokenFamily): Boolean {
        release()
        var created = false
        families.compute(family.id) { _, existing ->
            if (existing != null && existing.family.expiresAt.isAfter(now())) {
                existing
            } else {
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
            if (existing.version == version && existing.family.expiresAt.isAfter(now())) {
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
