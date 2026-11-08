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

package me.ahoo.cosec.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.codec.ByteArrayCodec
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.shadow.CoCacheCandidateStore
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * What CoSec reads where a deployment of CoSec 8.0.0-alpha.2 (on CoCache 6) left a value: CoCache 6's format 1, which
 * CoCache 8 does not read. Every family read through CoCache must fail closed on it, never read it as absent, until
 * `cosec-migrate cocache8` rewrites it: above all the revocation tables, whose `unknown-read: absent` would otherwise
 * accept a revoked token again. Memberships, a cache of what the loader says, are the exception: they load again.
 * Real Redis, values written byte for byte as CoCache 6 stores them.
 */
class CoCache6ValuesTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val tenant = TenantId("acme")
    private val alice = PrincipalId("alice")

    @AfterEach
    fun close() {
        redis.close()
    }

    private fun storageKey(cacheName: String, encodedKey: String): String =
        "${CoSecCacheSpecs.storagePath(keyPrefix, cacheName)}:$encodedKey"

    /** Writes [payload] under [key] as CoCache 6 did: a 36-byte header (magic, format 1, state), then the payload. */
    private fun cocache6(key: String, payload: String, state: Char = 'V') {
        val value = byteArrayOf(MAGIC, 1, state.code.toByte(), 0) + ByteArray(TOKEN_AND_TIME) + payload.encodeToByteArray()
        val client = RedisClient.create("redis://localhost:6379")
        try {
            client.connect(ByteArrayCodec.INSTANCE).use { it.sync().set(key.encodeToByteArray(), value) }
        } finally {
            client.shutdown()
        }
    }

    /** [read] fails because of what CoCache 6 wrote (a `ForeignFormatException` in its causes), not for another reason. */
    private fun foreign(read: () -> Any?) {
        val error = runCatching { read() }.exceptionOrNull()
        error.assert().describedAs("a CoCache 6 value was read as a value").isNotNull()
        generateSequence(error) { it.cause }.any { it is ForeignFormatException }.assert()
            .describedAs(error.toString()).isTrue()
    }

    @Test
    fun aRevocationCoCache6WroteIsAnErrorNeverNotRevoked() = runBlocking<Unit> {
        cocache6(storageKey(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, "jti-6"), "true")
        cocache6(storageKey(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, "jti-6-absent"), "", state = 'M')
        cocache6(storageKey(CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, RevokedSubjectCache.key(tenant, alice)), "1761955200000")
        CoSecTestCaches(redis, keyPrefix).use { caches ->
            val store = CoCacheTokenStore(caches.revokedTokenCache, caches.revokedSubjectCache)
            foreign { runBlocking { store.reader.isRevoked("jti-6") } }
            foreign { store.isRevoked("jti-6") }
            // A cached absence CoCache 6 wrote is no answer either.
            foreign { runBlocking { store.reader.isRevoked("jti-6-absent") } }
            foreign { runBlocking { store.reader.subjectRevokedBefore(tenant, alice) } }
            // and none of it is reclaimed: the value stays for cosec-migrate to rewrite.
            foreign { runBlocking { store.reader.isRevoked("jti-6") } }
        }
    }

    @Test
    fun aSnapshotDocumentCoCache6WroteFailsClosed() {
        cocache6(storageKey(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, SnapshotKeyEncoder.encode(SnapshotKey.Tenant("acme"))), "{}")
        SnapshotNode(redis, keyPrefix).use { node ->
            foreign { node.store.read(SnapshotKey.Tenant("acme")).get() }
        }
    }

    @Test
    fun controlPlaneRecordsCoCache6WroteFailClosed() = runBlocking<Unit> {
        val key = SnapshotKey.Tenant("acme")
        cocache6(storageKey(CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME, SnapshotKeyEncoder.encode(key)), "{}")
        cocache6(storageKey(CoSecCacheSpecs.STAGE_CACHE_NAME, SnapshotKeyEncoder.encode(key)), "{}")
        cocache6(storageKey(CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME, "documents"), "{}")
        SnapshotNode(redis, keyPrefix).use { node ->
            val store = CoCacheControlStore.create(CoCacheFactory(node.runtime), node.cache, keyPrefix)
            foreign { runBlocking { store.index(key) } }
            foreign { runBlocking { store.stage(key) } }
            foreign { runBlocking { store.documents() } }
        }
    }

    @Test
    fun aShadowCandidateCoCache6WroteFailsClosed() = runBlocking<Unit> {
        val key = SnapshotKey.Tenant("acme")
        cocache6(storageKey(CoSecCacheSpecs.CANDIDATE_CACHE_NAME, SnapshotKeyEncoder.encode(key)), "{}")
        CoSecTestCaches(redis, keyPrefix).use { caches ->
            val candidates = CoCacheCandidateStore.create(caches.factory, keyPrefix)
            foreign { runBlocking { candidates.candidate(key) } }
        }
    }

    /** Memberships cache what the loader answers: one CoCache 6 wrote is loaded again, never read as "none". */
    @Test
    fun membershipsCoCache6WroteAreLoadedAgain() = runBlocking<Unit> {
        val stale = Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to setOf(RoleId("stale"))), emptyList())
        cocache6(storageKey(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME, MembershipCache.key(tenant, alice)), MembershipsCodec.encode(stale).decodeToString())
        val loaded = Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to setOf(RoleId("admin"))), emptyList())
        val loads = AtomicInteger()
        CoSecTestCaches(redis, keyPrefix).use { caches ->
            val provider = CoCacheMembershipProvider(caches.membershipCache) { _, _ ->
                loads.incrementAndGet()
                MembershipAnswer.Known(loaded)
            }
            (provider.memberships(tenant, alice) as MembershipAnswer.Known).memberships.assert().isEqualTo(loaded)
            loads.get().assert().isEqualTo(1)
        }
    }

    private companion object {
        const val MAGIC: Byte = 0xC0.toByte()
        const val TOKEN_AND_TIME = 32
    }
}
