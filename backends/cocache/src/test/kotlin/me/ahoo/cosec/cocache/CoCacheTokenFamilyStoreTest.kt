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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenFamilies
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * RFC 0009: token families on real Redis, one conditional write of one key at a time, shared by two instances. The
 * race and the rules are those of `TokenFamiliesTest`, over the store a deployment runs.
 */
class CoCacheTokenFamilyStoreTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val backend = HookedBackend(redis)
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val instanceA = CoSecTestCaches(redis, keyPrefix)
    private val instanceB = CoSecTestCaches(backend, keyPrefix)
    private val storeA = CoCacheTokenFamilyStore(instanceA.tokenFamilyCache)
    private val storeB = CoCacheTokenFamilyStore(instanceB.tokenFamilyCache)
    private val familiesA = TokenFamilies(storeA)
    private val familiesB = TokenFamilies(storeB)

    @AfterEach
    fun close() {
        instanceA.close()
        instanceB.close()
        redis.close()
    }

    private fun token(session: TokenSession) =
        VerifiedToken("jti-${session.generation}", "alice", "acme", null, session = session)

    @Test
    fun `a family reads back as written, and expires with its session`() = runBlocking<Unit> {
        val issue = familiesA.start(TenantId("acme"), PrincipalId("alice"))
        val read = checkNotNull(storeB.read(issue.session.id))
        read.family.generation.assert().isEqualTo(1L)
        read.family.expiresAt.assert().isEqualTo(issue.notAfter)
        storeB.create(read.family).assert().isFalse()
        storeB.replace(read.version, read.family.rotated(Instant.now())).assert().isTrue()
        storeA.replace(read.version, read.family.revoked()).assert().isFalse()
        storeA.read("never-started").assert().isNull()

        val short =
            TokenFamily(
                "short",
                TenantId("acme"),
                PrincipalId("alice"),
                Instant.now(),
                Instant.now().plusSeconds(1),
                1,
                null,
                false
            )
        storeA.create(short).assert().isTrue()
        Thread.sleep(2_500)
        storeA.read("short").assert().isNull()
    }

    /** IDN-15 on Redis: two instances racing to refresh one pair move the family once, and every caller gets a pair. */
    @Test
    fun `refreshes of one pair racing on two instances move the family once`() = runBlocking<Unit> {
        val session = familiesA.start(TenantId("acme"), PrincipalId("alice")).session
        val issued = (1..32).map { attempt ->
            val families = if (attempt % 2 == 0) familiesA else familiesB
            async(Dispatchers.Default) { families.rotate(token(session)).session }
        }.awaitAll()
        issued.map { it.generation }.toSet().assert().containsExactly(2L)
        val family = checkNotNull(storeA.read(session.id)).family
        family.generation.assert().isEqualTo(2L)
        family.revoked.assert().isFalse()
    }

    /** IDN-16 and IDN-18 on Redis: a revocation is a mark another instance reads, and every pair of the family stops. */
    @Test
    fun `logout on one instance ends the family on the other`() = runBlocking<Unit> {
        val session = familiesA.start(TenantId("acme"), PrincipalId("alice")).session
        val next = familiesB.rotate(token(session)).session
        familiesA.end(session.id)
        assertThrows<TokenRevokedException> { runBlocking { familiesB.rotate(token(next)) } }
        checkNotNull(storeB.read(session.id)).family.revoked.assert().isTrue()
    }

    /** IDN-19 on Redis: a read Redis cannot answer is no family and no refusal: it throws, and nothing is issued. */
    @Test
    fun `an unreadable family record issues nothing`() = runBlocking<Unit> {
        val session = familiesA.start(TenantId("acme"), PrincipalId("alice")).session
        backend.failAll = true
        assertThrows<Exception> { runBlocking { familiesB.rotate(token(session)) } }
            .assert().isNotInstanceOf(TokenRevokedException::class.java)
        backend.failAll = false
        checkNotNull(storeA.read(session.id)).family.generation.assert().isEqualTo(1L)
    }

    @Test
    fun `the family table reads a failure as unknown, and no layer changes that`() {
        CoSecCacheSpecs.tokenFamily().unknownRead.assert().isEqualTo(me.ahoo.cocache.core.policy.UnknownReadMode.THROW)
        CoSecCacheSpecs.locks(
            CoSecCacheSpecs.TOKEN_FAMILY_CACHE_NAME
        ).isLocked(PolicyField.UNKNOWN_READ).assert().isTrue()
        (CoSecCacheSpecs.tokenFamily().keyPrefix + CoSecCacheSpecs.tokenFamily().namespace)
            .assert().isEqualTo("cosec:token:family")
    }

    @Test
    fun `a stored family is strict`() {
        val family =
            TokenFamily(
                "f",
                TenantId("acme"),
                PrincipalId("alice"),
                Instant.EPOCH,
                Instant.EPOCH.plus(Duration.ofDays(1)),
                2,
                Instant.EPOCH,
                true
            )
        TokenFamilyCodec.decode(TokenFamilyCodec.encode(family)).assert().isEqualTo(family)
        assertThrows<Exception> { TokenFamilyCodec.decode("""{"id":"f"}""".toByteArray()) }
        val extra = String(TokenFamilyCodec.encode(family)).replace("}", ""","more":1}""")
        assertThrows<Exception> { TokenFamilyCodec.decode(extra.toByteArray()) }
    }
}
