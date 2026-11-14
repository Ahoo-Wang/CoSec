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
 * its end reads as absent, as on Redis.
 */
class InMemoryTokenFamilyStore(private val now: () -> Instant = Instant::now) : TokenFamilyStore {
    private class Entry(val family: TokenFamily, val version: String)

    private val families = ConcurrentHashMap<String, Entry>()
    private val versions = AtomicLong()

    private fun live(id: String): Entry? = families[id]?.takeIf { it.family.expiresAt.isAfter(now()) }

    override suspend fun create(family: TokenFamily): Boolean {
        var created = false
        families.compute(family.id) { _, existing ->
            if (existing != null && existing.family.expiresAt.isAfter(now())) {
                existing
            } else {
                created = true
                Entry(family, versions.incrementAndGet().toString())
            }
        }
        return created
    }

    override suspend fun read(id: String): TokenFamilyStore.Read? = live(id)?.let {
        TokenFamilyStore.Read(it.family, it.version)
    }

    override suspend fun replace(version: String, next: TokenFamily): Boolean {
        var replaced = false
        families.computeIfPresent(next.id) { _, existing ->
            if (existing.version == version && existing.family.expiresAt.isAfter(now())) {
                replaced = true
                Entry(next, versions.incrementAndGet().toString())
            } else {
                existing
            }
        }
        return replaced
    }
}
