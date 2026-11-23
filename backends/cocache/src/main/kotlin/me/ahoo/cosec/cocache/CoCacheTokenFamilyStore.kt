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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore.Companion.cocacheDigest
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore.Companion.snapshotDigest
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.token.InMemorySessionIndex
import me.ahoo.cosec.spi.token.SessionIndex
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Token families (RFC 0009) in Redis, one key per live session: `<keyPrefix>:token:family:<id>`. Every write is one
 * conditional write of that key (`putIfMatch`, one Lua script), so it works on a Redis Cluster and two refreshes of one
 * generation cannot both move the family on. Each write keeps the key for the shorter of what is left of the family's
 * lifetime and [refreshValidity] (DR1-G-2): a session nobody refreshes leaves Redis with its last refresh token, not at
 * the end of its lifetime. A revoked one is marked, not deleted, and is kept as long from its revocation.
 *
 * [sessions] keeps [TokenFamilyStore.SESSIONS_PER_PRINCIPAL] sessions of a principal in a tenant, those that began
 * last. [create] admits a family there first and writes its record after, so no record escapes the count, and deletes
 * the records it pushes out: deleted, not marked, since a marked record is kept as long as a live one. That delete is
 * another key, so it is not atomic with the login: one that fails is logged and the login goes on, and the record it
 * missed leaves Redis with its refresh token.
 *
 * It fails closed: a read Redis cannot answer throws (`unknown-read: throw`, [CoSecCacheSpecs.locks]), and so does a
 * write whose outcome is unknown, which is never read back as done: the caller issues nothing.
 */
class CoCacheTokenFamilyStore @InternalCoSecApi constructor(
    private val cache: Store<String, TokenFamily>,
    /** The refresh token validity (`cosec.jwt.token-validity.refresh`), or null to keep each family to its end. */
    private val refreshValidity: Duration? = null,
    /** Where each principal's sessions are counted: Redis on a deployment, this process's memory otherwise. */
    private val sessions: SessionIndex = InMemorySessionIndex(),
    private val clock: Clock = Clock.systemUTC(),
) : TokenFamilyStore {
    init {
        require(refreshValidity == null || refreshValidity.isPositive) {
            "A refresh token validity is positive, not $refreshValidity."
        }
    }

    override suspend fun create(family: TokenFamily): Boolean {
        val now = clock.instant()
        val keepFor = Duration.between(now, family.expiresAt).coerceAtLeast(Duration.ofSeconds(1))
        sessions.admit(family, now, keepFor, TokenFamilyStore.SESSIONS_PER_PRINCIPAL).forEach { delete(it) }
        return cache.suspending().putIfMatch(key(family.id), null, family, lifetime(family)) is ConditionalPut.Written
    }

    /** Deletes a session pushed out of its principal's count, as well as it can: a login does not fail on it. */
    // Any failure of a best-effort delete is logged; cancellation is rethrown.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun delete(id: String) {
        try {
            cache.suspending().delete(key(id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn(failure) { "The session [$id], pushed out of its principal's latest sessions, was not deleted." }
        }
    }

    override suspend fun read(id: String): TokenFamilyStore.Read? =
        when (val read = cache.suspending().getIfChanged(key(id), null)) {
            is ConditionalGet.Present -> TokenFamilyStore.Read(read.value, snapshotDigest(read.digest).toString())
            ConditionalGet.Absent -> null
            ConditionalGet.Unchanged -> error("An unconditional read of family [$id] was answered as unchanged.")
        }

    override suspend fun replace(version: String, next: TokenFamily): Boolean {
        val expected = cocacheDigest(SnapshotDigest.parse(version))
        return cache.suspending().putIfMatch(key(next.id), expected, next, lifetime(next)) is ConditionalPut.Written
    }

    /**
     * How long a write keeps [family]: what is left of its lifetime, or less, the refresh token validity; at least a
     * second, since CoCache needs a positive lifetime.
     */
    private fun lifetime(family: TokenFamily): Duration {
        val left = Duration.between(clock.instant(), family.expiresAt)
        val kept = refreshValidity?.let { minOf(left, it) } ?: left
        return kept.coerceAtLeast(Duration.ofSeconds(1))
    }

    private fun key(id: String): String {
        require(id.isNotEmpty() && Identifiers.isWellFormed(id)) { "A token family id is not empty and well formed." }
        return id
    }
}
