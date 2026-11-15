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

import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore.Companion.cocacheDigest
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore.Companion.snapshotDigest
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import java.time.Clock
import java.time.Duration

/**
 * Token families (RFC 0009) in Redis, one key per live session: `<keyPrefix>:token:family:<id>`. Every write is one
 * conditional write of that key (`putIfMatch`, one Lua script), so it works on a Redis Cluster and two refreshes of one
 * generation cannot both move the family on. Each write gives the key the family's remaining lifetime, so a family
 * leaves Redis at its end; a revoked one is marked, not deleted, and stays until then.
 *
 * It fails closed: a read Redis cannot answer throws (`unknown-read: throw`, [CoSecCacheSpecs.locks]), and so does a
 * write whose outcome is unknown, which is never read back as done: the caller issues nothing.
 */
class CoCacheTokenFamilyStore @InternalCoSecApi constructor(
    private val cache: Store<String, TokenFamily>,
    private val clock: Clock = Clock.systemUTC(),
) : TokenFamilyStore {
    override suspend fun create(family: TokenFamily): Boolean =
        cache.suspending().putIfMatch(key(family.id), null, family, lifetime(family)) is ConditionalPut.Written

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

    /** What is left of [family]'s lifetime, at least a second: CoCache needs a positive lifetime. */
    private fun lifetime(family: TokenFamily): Duration =
        Duration.between(clock.instant(), family.expiresAt).coerceAtLeast(Duration.ofSeconds(1))

    private fun key(id: String): String {
        require(id.isNotEmpty() && Identifiers.isWellFormed(id)) { "A token family id is not empty and well formed." }
        return id
    }
}
