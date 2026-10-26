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

package me.ahoo.cosec.cache

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * RFC 0004 §4.5.3: CoCacheTokenStore's subject revocations against real Redis, with two instances sharing it.
 */
internal class CoCacheTokenStoreSubjectIntegrationTest {
    private val backend = CoSecTestCaches.redisBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val instanceA = CoSecTestCaches(backend, keyPrefix)
    private val instanceB = CoSecTestCaches(backend, keyPrefix)
    private val tokenStoreA = CoCacheTokenStore(instanceA.revokedTokenCache, instanceA.revokedSubjectCache)
    private val tokenStoreB = CoCacheTokenStore(instanceB.revokedTokenCache, instanceB.revokedSubjectCache)
    private val tenant = TenantId("tenant")
    private val issuedBefore = Instant.ofEpochMilli(1_790_000_000_123)

    private fun principal() = PrincipalId(MockIdGenerator.INSTANCE.generateAsString())

    @AfterEach
    fun destroy() {
        instanceA.close()
        instanceB.close()
        backend.close()
    }

    @Test
    fun revokeSubjectUntilTtlExpires() {
        val principal = principal()
        tokenStoreA.subjectRevokedBefore(tenant, principal).assert().isNull()

        tokenStoreA.revokeSubject(tenant, principal, issuedBefore, REVOCATION_TTL)

        tokenStoreA.subjectRevokedBefore(tenant, principal).assert().isEqualTo(issuedBefore)
        tokenStoreA.subjectRevokedBefore(TenantId("other"), principal).assert().isNull()
        // the entry lives for the ttl plus one second
        Thread.sleep(REVOCATION_TTL.toMillis() + MARGIN_MS)
        tokenStoreA.subjectRevokedBefore(tenant, principal).assert().isNull()
    }

    @Test
    fun revocationOnOneInstanceReplacesCachedNotRevokedOnAnother() {
        val principal = principal()
        // instance B caches "not revoked" locally
        tokenStoreB.subjectRevokedBefore(tenant, principal).assert().isNull()

        tokenStoreA.revokeSubject(tenant, principal, issuedBefore, Duration.ofMinutes(1))

        val deadline = System.nanoTime() + Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS / 2).toNanos()
        while (tokenStoreB.subjectRevokedBefore(tenant, principal) == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
        }
        tokenStoreB.subjectRevokedBefore(tenant, principal).assert().isEqualTo(issuedBefore)
    }

    @Test
    fun anEarlierRevocationNeverShortensALaterOne() {
        val principal = principal()
        tokenStoreA.revokeSubject(tenant, principal, issuedBefore, Duration.ofMinutes(1))

        tokenStoreB.revokeSubject(tenant, principal, issuedBefore.minusSeconds(60), Duration.ofMinutes(1))
        tokenStoreA.subjectRevokedBefore(tenant, principal).assert().isEqualTo(issuedBefore)

        tokenStoreB.revokeSubject(tenant, principal, issuedBefore.plusSeconds(60), Duration.ofMinutes(1))
        tokenStoreB.subjectRevokedBefore(tenant, principal).assert().isEqualTo(issuedBefore.plusSeconds(60))
    }

    @Test
    fun keysAreUnambiguous() {
        tokenStoreA.revokeSubject(TenantId("a:b"), PrincipalId("c"), issuedBefore, Duration.ofMinutes(1))

        tokenStoreA.subjectRevokedBefore(TenantId("a"), PrincipalId("b:c")).assert().isNull()
        RevokedSubjectCache.key(TenantId("a:b"), PrincipalId("c"))
            .assert().isNotEqualTo(RevokedSubjectCache.key(TenantId("a"), PrincipalId("b:c")))
    }

    @Test
    fun tokenRevocationsAndSubjectRevocationsStayApart() {
        val principal = principal()
        tokenStoreA.revokeSubject(tenant, principal, issuedBefore, Duration.ofMinutes(1))

        tokenStoreA.isRevoked(RevokedSubjectCache.key(tenant, principal)).assert().isFalse()
    }

    @Test
    fun withoutSubjectCacheItCannotRevokeSubjects() {
        val tokenStore = CoCacheTokenStore(instanceA.revokedTokenCache)

        assertThrows<UnsupportedOperationException> {
            tokenStore.revokeSubject(tenant, principal(), issuedBefore, Duration.ofMinutes(1))
        }
        tokenStore.subjectRevokedBefore(tenant, principal()).assert().isNull()
    }

    companion object {
        private val REVOCATION_TTL = Duration.ofSeconds(1)
        private const val MARGIN_MS = 2_000L
        private const val POLL_MS = 50L
    }
}
