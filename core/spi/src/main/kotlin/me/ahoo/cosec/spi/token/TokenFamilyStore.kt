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

/**
 * What the store keeps of one session (RFC 0009): whose it is, when its user signed in ([authTime]), when it ends for
 * good ([expiresAt], the absolute lifetime), its current [generation], when that generation became current
 * ([rotatedAt], null for the first), and whether it was [revoked]. A revoked family is kept, marked, as long as any
 * pair of it could still be presented, so it is never mistaken for one that expired.
 *
 * A session a tenant switch started names its [root]: the session its user's login began, whichever session the switch
 * was made from (`IDN-36`). It lives only while its root does: a root ended, revoked or gone ends it at its next refresh
 * or switch. A store must keep [root]: one that drops it turns the session into one that outlives its login.
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
    /** The session the login began, for a session a tenant switch started; null for the login's own session. */
    val root: String?,
) {
    constructor(
        id: String,
        tenant: TenantId,
        principal: PrincipalId,
        authTime: Instant,
        expiresAt: Instant,
        generation: Long,
        rotatedAt: Instant?,
        revoked: Boolean,
    ) : this(id, tenant, principal, authTime, expiresAt, generation, rotatedAt, revoked, null)

    init {
        require(id.isNotEmpty()) { "A token family has an id." }
        require(generation >= 1) { "A token family's generation is positive, not $generation." }
        require(expiresAt.isAfter(authTime)) { "A token family ends after its login, not at $expiresAt." }
        require(root == null || (root.isNotEmpty() && root != id)) { "A token family's root is another session's id." }
    }

    /** This family at the next generation, current since [at]. */
    fun rotated(at: Instant): TokenFamily =
        TokenFamily(id, tenant, principal, authTime, expiresAt, generation + 1, at, revoked, root)

    /** This family, revoked: no pair of it refreshes any more. */
    fun revoked(): TokenFamily = TokenFamily(id, tenant, principal, authTime, expiresAt, generation, rotatedAt, true, root)

    private fun fields(): List<Any?> =
        listOf(id, tenant, principal, authTime, expiresAt, generation, rotatedAt, revoked, root)

    override fun equals(other: Any?): Boolean = other is TokenFamily && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "TokenFamily($id, generation $generation, revoked=$revoked" + (root?.let { ", root $it" } ?: "") + ")"
}

/**
 * Where token families are kept (RFC 0009): read and written only when a token is issued, refreshed or revoked, never
 * on a request. Every write is conditional, so of two refreshes of one generation at most one moves the family on,
 * and an implementation honours that across instances (one key, one conditional write).
 *
 * **How long a record is kept.** [TokenFamily.expiresAt] is an upper bound: a family is never kept past it. An
 * implementation may drop a record earlier once no pair of it can refresh any more, as CoSec's do: each write keeps
 * the record for the shorter of what is left of its lifetime and the refresh token validity, so a session nobody
 * refreshes leaves the store with its last refresh token, not at the end of its lifetime.
 *
 * **How many records a principal has.** An implementation limits the sessions of one principal in one tenant: it keeps
 * the [SESSIONS_PER_PRINCIPAL] that began most recently, and [create] of one more ends the one that began earliest by
 * deleting its record, whether it is live, revoked or already ended. Its next refresh is then refused as a session that
 * is unknown; its access token stays valid until it expires. Deleting, not marking, is what bounds the store: a marked
 * record would be kept as long as a live one.
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

    companion object {
        /**
         * How many sessions one principal keeps in one tenant: the most recent, counted by when they began. A constant:
         * nothing a process can observe says how many devices a person signs in from, and 64 is room for many devices
         * and many tenant switches.
         */
        const val SESSIONS_PER_PRINCIPAL: Int = 64
    }
}
