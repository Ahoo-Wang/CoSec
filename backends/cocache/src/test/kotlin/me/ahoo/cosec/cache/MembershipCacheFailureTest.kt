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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.MembershipResolver
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 0006 §5.5.2, `MEM-2`: the memberships cache is not the store of record, so its entries expire, but nothing that
 * goes wrong with it may read as "no memberships". Real Redis, with failures injected by [HookedBackend].
 */
class MembershipCacheFailureTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val backend = HookedBackend(redis)
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val instanceA = CoSecTestCaches(backend, keyPrefix)
    private val instanceB = CoSecTestCaches(redis, keyPrefix)
    private val tenant = TenantId("acme")
    private val alice = PrincipalId("alice")
    private val stored = Memberships(
        Memberships.Source.PROVIDER,
        mapOf(
            SpaceId.DEFAULT to setOf(RoleId("admin")),
            SpaceId("team-a") to setOf(RoleId("editor"), RoleId("viewer"))
        ),
        listOf(PolicyId("p1"), PolicyId("p2")),
    )
    private val loads = AtomicInteger()
    private val loader = MembershipLoader { _, _ ->
        loads.incrementAndGet()
        stored
    }
    private val providerA = CoCacheMembershipProvider(instanceA.membershipCache, loader)
    private val providerB = CoCacheMembershipProvider(instanceB.membershipCache, loader)

    @AfterEach
    fun close() {
        instanceA.close()
        instanceB.close()
        redis.close()
    }

    private fun Memberships.assertSameAs(expected: Memberships) {
        source.assert().isEqualTo(expected.source)
        roles.assert().isEqualTo(expected.roles)
        attachments.assert().isEqualTo(expected.attachments)
    }

    @Test
    fun aMissLoadsOnceAndEveryInstanceReadsTheCachedAnswer() = runBlocking<Unit> {
        providerA.memberships(tenant, alice)!!.assertSameAs(stored)
        providerA.memberships(tenant, alice)!!.assertSameAs(stored)
        instanceA.clearLocal(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME)
        providerA.memberships(tenant, alice)!!.assertSameAs(stored)
        providerB.memberships(tenant, alice)!!.assertSameAs(stored)
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun cachedMembershipsExpireInRedis() = runBlocking<Unit> {
        providerA.memberships(tenant, alice)
        val ttl = checkNotNull(instanceA.runtime.admin(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME))
            .inspect(MembershipCache.key(tenant, alice)).remote.ttl
        ttl.assert().isNotNull()
        ttl!!.assert().isLessThanOrEqualTo(CoSecCacheSpecs.DEFAULT_MEMBERSHIP_TTL).isPositive()
    }

    @Test
    fun anInvalidationLoadsAgain() = runBlocking<Unit> {
        providerA.memberships(tenant, alice)
        providerA.invalidate(tenant, alice)
        providerA.memberships(tenant, alice)
        loads.get().assert().isEqualTo(2)
    }

    /** The heart of it: a failed read throws, the loader is not asked, and the resolver turns it into a failure. */
    @Test
    fun aFailedReadIsNeverNoMemberships() = runBlocking<Unit> {
        backend.failAll = true
        assertThrows<Exception> { runBlocking { providerA.memberships(tenant, alice) } }
        loads.get().assert().isZero()

        val resolver = MembershipResolver(providerA)
        val failure = assertThrows<MembershipUnavailableException> {
            runBlocking { resolver.resolve(tenant, alice, MembershipSource.Provider.UNCAPPED) }
        }
        failure.message.assert().contains("failed")
    }

    @Test
    fun aFailedReadOfCachedMembershipsAlsoThrows() = runBlocking<Unit> {
        providerA.memberships(tenant, alice)
        instanceA.clearLocal(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME)
        backend.failAll = true
        assertThrows<Exception> { runBlocking { providerA.memberships(tenant, alice) } }
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun aFailedWriteThrows() = runBlocking<Unit> {
        backend.failWrites = true
        assertThrows<Exception> { runBlocking { providerA.memberships(tenant, alice) } }
    }

    /** RFC 0009 §4.2: an unknown principal is null, never "no memberships", and is asked again rather than cached. */
    @Test
    fun anUnknownPrincipalIsNullAndNotCached() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val unknown = CoCacheMembershipProvider(instanceA.membershipCache) { _, _ ->
            asked.incrementAndGet()
            null
        }
        unknown.memberships(tenant, alice).assert().isNull()
        unknown.memberships(tenant, alice).assert().isNull()
        asked.get().assert().isEqualTo(2)
    }

    /** A deactivated principal is cached like any other answer, and every instance reads it as inactive. */
    @Test
    fun anInactivePrincipalIsCachedAsInactive() = runBlocking<Unit> {
        val inactive = Memberships(Memberships.Source.PROVIDER, stored.roles, stored.attachments, active = false)
        CoCacheMembershipProvider(instanceA.membershipCache) { _, _ -> inactive }.memberships(tenant, alice)!!
            .active.assert().isFalse()
        CoCacheMembershipProvider(instanceB.membershipCache) { _, _ -> error("not asked") }.memberships(tenant, alice)!!
            .active.assert().isFalse()
    }

    @Test
    fun aFailingLoaderThrowsAndCachesNothing() = runBlocking<Unit> {
        val failing = CoCacheMembershipProvider(instanceA.membershipCache) { _, _ -> error("directory down") }
        assertThrows<IllegalStateException> { runBlocking { failing.memberships(tenant, alice) } }
        providerA.memberships(tenant, alice)!!.assertSameAs(stored)
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun aLoaderAnsweringClaimsIsRefused() = runBlocking<Unit> {
        val wrong = CoCacheMembershipProvider(instanceA.membershipCache) { _, _ ->
            MembershipClaims.parse(listOf("admin"), emptyList())
        }
        assertThrows<IllegalStateException> { runBlocking { wrong.memberships(tenant, alice) } }
    }

    @Test
    fun theCodecKeepsOrderAndRefusesWhatItDidNotWrite() {
        MembershipsCodec.decode(MembershipsCodec.encode(stored)).assertSameAs(stored)
        val claims = MembershipClaims.parse(listOf("a"), emptyList())
        assertThrows<IllegalArgumentException> { MembershipsCodec.encode(claims) }
        assertThrows<IllegalArgumentException> { MembershipsCodec.decode("""{"roles":{}}""".toByteArray()) }
        assertThrows<IllegalArgumentException> {
            MembershipsCodec.decode("""{"roles":{"":"admin"},"attachments":[]}""".toByteArray())
        }
        assertThrows<IllegalArgumentException> {
            MembershipsCodec.decode("""{"roles":{},"attachments":[1]}""".toByteArray())
        }
    }
}
