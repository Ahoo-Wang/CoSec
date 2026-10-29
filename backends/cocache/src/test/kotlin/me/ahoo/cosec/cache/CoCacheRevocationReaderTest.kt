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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * [CoCacheRevocationReader] against real Redis: it reads what [CoCacheTokenStore] writes, from another instance too,
 * answers a local hit without suspending (RFC 0006 T1), and keeps the documented degradation: an unreadable store
 * means not revoked.
 */
class CoCacheRevocationReaderTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val backend = HookedBackend(redis)
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val writer = CoSecTestCaches(redis, keyPrefix)
    private val reading = CoSecTestCaches(backend, keyPrefix)
    private val store = CoCacheTokenStore(writer.revokedTokenCache, writer.revokedSubjectCache)
    private val reader = CoCacheRevocationReader(reading.revokedTokenCache, reading.revokedSubjectCache)
    private val tenant = TenantId("acme")
    private val alice = PrincipalId("alice")

    @AfterEach
    fun close() {
        writer.close()
        reading.close()
        redis.close()
    }

    private fun tokenId(): String = MockIdGenerator.INSTANCE.generateAsString()

    @Test
    fun readsRevokedTokensFromRedis() = runBlocking<Unit> {
        val revoked = tokenId()
        store.revoke(revoked, Duration.ofMinutes(1))
        reader.isRevoked(revoked).assert().isTrue()
        reader.isRevoked(tokenId()).assert().isFalse()
    }

    @Test
    fun readsRevokedSubjectsFromRedis() = runBlocking<Unit> {
        val issuedBefore = Instant.parse("2026-10-27T00:00:00Z")
        reader.subjectRevokedBefore(tenant, alice).assert().isNull()
        store.revokeSubject(tenant, alice, issuedBefore, Duration.ofMinutes(1))
        reading.clearLocal(CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME)
        reader.subjectRevokedBefore(tenant, alice).assert().isEqualTo(issuedBefore)
        reader.subjectRevokedBefore(TenantId("other"), alice).assert().isNull()
    }

    @Test
    fun withoutASubjectCacheNoSubjectIsRevoked() = runBlocking<Unit> {
        CoCacheRevocationReader(reading.revokedTokenCache).subjectRevokedBefore(tenant, alice).assert().isNull()
    }

    @Test
    fun theStoreExposesItsReader() = runBlocking<Unit> {
        val revoked = tokenId()
        store.revoke(revoked, Duration.ofMinutes(1))
        store.reader.isRevoked(revoked).assert().isTrue()
    }

    /**
     * T1: once the answer is in the local tier, the read completes on the caller's thread without suspending. The write
     * goes through the reading instance, and the read is primed again until no invalidation in flight evicts the local
     * copy between priming and reading: the property is that a local hit never suspends, not when the copy settles.
     */
    @Test
    fun aLocalHitDoesNotSuspend() {
        val revoked = tokenId()
        CoCacheTokenStore(reading.revokedTokenCache).revoke(revoked, Duration.ofMinutes(1))
        var answer: Result<Boolean>? = null
        repeat(PRIMING_ATTEMPTS) {
            if (answer != null) return@repeat
            runBlocking { reader.isRevoked(revoked) }.assert().isTrue()
            val read: suspend () -> Boolean = { reader.isRevoked(revoked) }
            read.startCoroutine(Continuation(EmptyCoroutineContext) { answer = it })
            if (answer == null) Thread.sleep(PRIMING_PAUSE_MS)
        }
        answer.assert().describedAs("a read completed before startCoroutine returned").isNotNull()
        answer!!.getOrThrow().assert().isTrue()
    }

    /** The documented exception to failing closed (AGENTS.md): Redis down means not revoked, not an error. */
    @Test
    fun anUnreadableStoreMeansNotRevoked() = runBlocking<Unit> {
        val revoked = tokenId()
        store.revoke(revoked, Duration.ofMinutes(1))
        store.revokeSubject(tenant, alice, Instant.now(), Duration.ofMinutes(1))
        backend.failAll = true
        reader.isRevoked(revoked).assert().isFalse()
        reader.subjectRevokedBefore(tenant, alice).assert().isNull()
    }

    private companion object {
        const val PRIMING_ATTEMPTS = 50
        const val PRIMING_PAUSE_MS = 20L
    }
}
