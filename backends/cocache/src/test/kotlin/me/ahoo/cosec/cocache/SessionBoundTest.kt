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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * DR1-G-2 on real Redis, two instances sharing it: a session record is kept no longer than its refresh token, and a
 * principal keeps the [TokenFamilyStore.SESSIONS_PER_PRINCIPAL] sessions that began last in a tenant, the earliest
 * deleted, whatever instance signed it in.
 */
class SessionBoundTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val instanceA = CoSecTestCaches(redis, keyPrefix)
    private val instanceB = CoSecTestCaches(redis, keyPrefix)
    private val indexPrefix = "$keyPrefix:token:sessions:"
    private val refresh = Duration.ofDays(7)
    private val storeA = CoCacheTokenFamilyStore(
        instanceA.tokenFamilyCache,
        refresh,
        RedisSessionIndex(ReactiveStringRedisTemplate(factory), indexPrefix),
    )
    private val storeB = CoCacheTokenFamilyStore(
        instanceB.tokenFamilyCache,
        refresh,
        RedisSessionIndex(ReactiveStringRedisTemplate(factory), indexPrefix),
    )
    private val familiesA = TokenFamilies(storeA, Duration.ofDays(30))
    private val familiesB = TokenFamilies(storeB, Duration.ofDays(30))
    private val redisKeys = StringRedisTemplate(factory)
    private val tenant = TenantId("acme")
    private val alice = PrincipalId("alice")

    @AfterEach
    fun close() {
        instanceA.close()
        instanceB.close()
        redis.close()
    }

    private fun recordKey(id: String) = "$keyPrefix:token:family:$id"

    @Test
    fun `a record is kept no longer than its refresh token, from each write`(): Unit = runBlocking {
        val issue = familiesA.start(tenant, alice)
        val ttl = redisKeys.getExpire(recordKey(issue.session.id), TimeUnit.SECONDS)
        // Not the 30 days of the session: the 7 of its refresh token.
        ttl.assert().isBetween(refresh.seconds - 5, refresh.seconds + 1)
    }

    @Test
    fun `a principal keeps the sessions that began last, and the earliest is deleted`(): Unit = runBlocking {
        val limit = TokenFamilyStore.SESSIONS_PER_PRINCIPAL
        val first = familiesA.start(tenant, alice).session.id
        // A revoked session takes its place as a live one does: a login and a logout do not get round the bound.
        val revoked = familiesB.start(tenant, alice).session
        familiesB.end(revoked.id)
        val rest = (3..limit).map { (if (it % 2 == 0) familiesA else familiesB).start(tenant, alice).session.id }
        storeA.read(first).assert().isNotNull()
        redisKeys.opsForZSet().size("${indexPrefix}4:acme:alice").assert().isEqualTo(limit.toLong())

        val next = familiesB.start(tenant, alice).session.id

        storeA.read(first).assert().isNull()
        redisKeys.hasKey(recordKey(first)).assert().isFalse()
        storeB.read(revoked.id).assert().isNotNull()
        (rest + next).forEach { storeA.read(it).assert().describedAs(it).isNotNull() }
        redisKeys.opsForZSet().size("${indexPrefix}4:acme:alice").assert().isEqualTo(limit.toLong())

        familiesA.start(tenant, alice)
        storeB.read(revoked.id).assert().isNull()
    }

    @Test
    fun `the bound is per principal and per tenant`(): Unit = runBlocking {
        val bob = familiesA.start(tenant, PrincipalId("bob")).session.id
        val elsewhere = familiesA.start(TenantId("other"), alice).session.id
        repeat(TokenFamilyStore.SESSIONS_PER_PRINCIPAL + 1) { familiesB.start(tenant, alice) }
        storeA.read(bob).assert().isNotNull()
        storeA.read(elsewhere).assert().isNotNull()
    }

    /** IDN-36 with IDN-35: every switch back to the login's tenant begins the login again, so it is never pushed out. */
    @Test
    fun `switching back to the login's tenant keeps the login among the latest`(): Unit = runBlocking {
        val login = familiesA.start(tenant, alice).session
        repeat(TokenFamilyStore.SESSIONS_PER_PRINCIPAL * 2) {
            (if (it % 2 == 0) familiesA else familiesB).startAt(tenant, alice, login.authTime, login.id)
        }
        storeB.read(login.id).assert().isNotNull()
    }

    @Test
    fun `the index lives as long as the longest session it counts`(): Unit = runBlocking {
        familiesA.start(tenant, alice)
        val ttl = redisKeys.getExpire("${indexPrefix}4:acme:alice", TimeUnit.SECONDS)
        ttl.assert().isBetween(Duration.ofDays(30).seconds - 5, Duration.ofDays(30).seconds + 1)
    }

    companion object {
        private lateinit var factory: LettuceConnectionFactory

        @BeforeAll
        @JvmStatic
        fun setup() {
            factory = LettuceConnectionFactory(RedisStandaloneConfiguration())
            factory.afterPropertiesSet()
        }

        @AfterAll
        @JvmStatic
        fun destroy() {
            factory.destroy()
        }
    }
}
