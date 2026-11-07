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
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.control.ReferenceKey
import me.ahoo.cosec.cache.control.ReferenceKeyEncoder
import me.ahoo.cosec.cache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.cache.limiter.ReactiveRedisRateLimiterTest
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.engine.flat.CompiledRateLimit
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * RC1-52 on real Redis: Redis keys are UTF-8, as Spring's string template writes them, so an unpaired UTF-16 surrogate
 * is written as `?`. An id holding one would share its Redis key with the id that has `?` in its place: tenant
 * `beta\uD800` would read tenant `beta?`'s document. No id with one names a key, so no two ids share one (`IDN-10`);
 * CoCache 8 refuses such a key as well, CoCache 6 wrote it.
 */
class UnpairedSurrogateKeyTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val tenant = TenantId("acme")

    @AfterEach
    fun close() {
        redis.close()
    }

    /** What [read] did: the value it read, or that it was refused. */
    private fun <T> refused(read: () -> T): Throwable? = runCatching(read).fold({ value ->
        throw AssertionError("An id with an unpaired surrogate was read as another id's key, which holds [$value].")
    }) { it }

    @Test
    fun theUtf8EncodingOfAnUnpairedSurrogateIsAQuestionMark() {
        // The mechanism, which no CoSec change alters: why the ids below would share their keys.
        "beta\uD800".encodeToByteArray().assert().isEqualTo("beta?".encodeToByteArray())
        "beta\uD800".toByteArray(Charsets.UTF_8).assert().isEqualTo("beta?".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun aTenantIdWithAnUnpairedSurrogateNeverReadsAnotherTenantsDocument() {
        SnapshotNode(redis, keyPrefix).use { it.writer.publish(SnapshotKey.Tenant("beta?")).block() }
        SnapshotNode(redis, keyPrefix).use { reader ->
            (reader.store.read(SnapshotKey.Tenant("beta?")).get() is SnapshotRead.Present).assert().isTrue()
            refused { reader.store.read(SnapshotKey.Tenant("beta\uD800")).get() }
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun anAppIdWithAnUnpairedSurrogateNeverReadsAnotherAppsDocument() {
        SnapshotNode(redis, keyPrefix).use { it.writer.publish(SnapshotKey.App("shop?")).block() }
        SnapshotNode(redis, keyPrefix).use { reader ->
            refused { reader.store.read(SnapshotKey.App("shop\uDC00")).get() }
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun aPrincipalIdWithAnUnpairedSurrogateNeverReadsAnotherPrincipalsMemberships() = runBlocking<Unit> {
        val loads = AtomicInteger()
        val loader = MembershipLoader { _, _ ->
            loads.incrementAndGet()
            MembershipAnswer.Known(
                Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to setOf(RoleId("admin"))), emptyList())
            )
        }
        CoSecTestCaches(redis, keyPrefix).use { writer ->
            CoCacheMembershipProvider(
                writer.membershipCache,
                loader = loader
            ).memberships(tenant, PrincipalId("alice?"))
        }
        CoSecTestCaches(redis, keyPrefix).use { reader ->
            val provider = CoCacheMembershipProvider(reader.membershipCache, loader = loader)
            refused { runBlocking { provider.memberships(tenant, PrincipalId("alice\uD800")) } }
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun aTokenIdWithAnUnpairedSurrogateIsNeitherRevokedNorReadAsAnotherToken() = runBlocking<Unit> {
        CoSecTestCaches(redis, keyPrefix).use { caches ->
            val store = CoCacheTokenStore(caches.revokedTokenCache, caches.revokedSubjectCache)
            store.revoke("jti?", Duration.ofMinutes(1))
            refused { runBlocking { store.reader.isRevoked("jti\uD800") } }
                .assert().isInstanceOf(IllegalArgumentException::class.java)
            refused { store.isRevoked("jti\uD800") }.assert().isInstanceOf(IllegalArgumentException::class.java)
            // Revoking it would revoke the other token.
            refused { store.revoke("other\uD800", Duration.ofMinutes(1)) }
                .assert().isInstanceOf(IllegalArgumentException::class.java)
            store.isRevoked("other?").assert().isFalse()
        }
    }

    @Test
    fun aPrincipalWithAnUnpairedSurrogateIsNeitherRevokedNorReadAsAnotherPrincipal() = runBlocking<Unit> {
        CoSecTestCaches(redis, keyPrefix).use { caches ->
            val store = CoCacheTokenStore(caches.revokedTokenCache, caches.revokedSubjectCache)
            store.revokeSubject(tenant, PrincipalId("alice?"), Instant.now(), Duration.ofMinutes(1))
            refused { runBlocking { store.reader.subjectRevokedBefore(tenant, PrincipalId("alice\uD800")) } }
                .assert().isInstanceOf(IllegalArgumentException::class.java)
            refused {
                store.revokeSubject(
                    TenantId("beta\uD800"),
                    PrincipalId("bob"),
                    Instant.now(),
                    Duration.ofMinutes(1)
                )
            }
                .assert().isInstanceOf(IllegalArgumentException::class.java)
            store.subjectRevokedBefore(TenantId("beta?"), PrincipalId("bob")).assert().isNull()
        }
    }

    /** The control store's back-references name a policy by id: one with an unpaired surrogate names no key either. */
    @Test
    fun aPolicyIdWithAnUnpairedSurrogateNamesNoReferenceKey() {
        val tenant = SnapshotKey.Tenant("acme")
        ReferenceKeyEncoder.encode(ReferenceKey(tenant, PolicyId("p?"))).assert().isEqualTo("tenant:acme:p?")
        refused { ReferenceKeyEncoder.encode(ReferenceKey(tenant, PolicyId("p\uD800"))) }
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * A bucket value with an unpaired surrogate counts in a bucket of its own, never in the bucket of `x?`: the engine
     * names its bucket escaped ([CompiledRateLimit.keyedBucket]), as the limiter's Spring template would otherwise write
     * `?` for it. Neither is a bypass: each value is counted, in its own bucket.
     */
    @Test
    fun aBucketValueWithAnUnpairedSurrogateSharesNoBucket() = runBlocking<Unit> {
        val factory = ReactiveRedisRateLimiterTest.connectionFactory("localhost", 6379)
        try {
            val limiter =
                ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), "cosec:test:rate:${UUID.randomUUID()}")
            val limit = RateLimitSpec(1.0)
            limiter.tryAcquire("rule", CompiledRateLimit.keyedBucket("x?"), limit).allowed.assert().isTrue()
            limiter.tryAcquire("rule", CompiledRateLimit.keyedBucket("x\uD800"), limit).allowed
                .assert().describedAs("x\\uD800 counts in the bucket of x?").isTrue()
            limiter.tryAcquire("rule", CompiledRateLimit.keyedBucket("x\uD800"), limit).allowed
                .assert().describedAs("x\\uD800 is counted").isFalse()
            limiter.tryAcquire("rule", CompiledRateLimit.keyedBucket("x?"), limit).allowed.assert().isFalse()
        } finally {
            factory.destroy()
        }
    }
}
