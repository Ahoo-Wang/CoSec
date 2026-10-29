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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.MembershipResolver
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration.Companion.milliseconds

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
        MembershipAnswer.Known(stored)
    }
    private val providerA = CoCacheMembershipProvider(instanceA.membershipCache, loader = loader)
    private val providerB = CoCacheMembershipProvider(instanceB.membershipCache, loader = loader)

    @AfterEach
    fun close() {
        instanceA.close()
        instanceB.close()
        redis.close()
    }

    private fun MembershipAnswer.known(): Memberships = (this as MembershipAnswer.Known).memberships

    private fun Memberships.assertSameAs(expected: Memberships) {
        source.assert().isEqualTo(expected.source)
        roles.assert().isEqualTo(expected.roles)
        attachments.assert().isEqualTo(expected.attachments)
    }

    @Test
    fun aMissLoadsOnceAndEveryInstanceReadsTheCachedAnswer() = runBlocking<Unit> {
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        instanceA.clearLocal(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME)
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        providerB.memberships(tenant, alice).known().assertSameAs(stored)
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

    /**
     * A fill Redis cannot store still answers what the loader said, the truth, and keeps it on this instance only: other
     * instances load for themselves, and an invalidation still reaches every instance. Never a value nobody loaded.
     */
    @Test
    fun aFillRedisCannotStoreAnswersTheLoadedMemberships() = runBlocking<Unit> {
        backend.failFill = true
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        backend.failFill = false
        providerB.memberships(tenant, alice).known().assertSameAs(stored)
        loads.get().assert().isEqualTo(2)
    }

    /**
     * RFC 0006 §5.5.2: the request after a fill is a local hit, answered on the caller's thread. A `put` in place of the
     * read-through fill is a write, after which the next request read Redis once more (found by `SliceBlockHoundTest`).
     */
    @Test
    fun theRequestAfterAFillIsALocalHit() = runBlocking<Unit> {
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        val next = CompletableFuture<MembershipAnswer>()
        val call: suspend () -> MembershipAnswer = { providerA.memberships(tenant, alice) }
        call.startCoroutine(Continuation(EmptyCoroutineContext) { next.complete(it.getOrThrow()) })
        next.isDone.assert().describedAs("the request after the fill suspended").isTrue()
        loads.get().assert().isEqualTo(1)
    }

    /**
     * RFC 0009 §4.2: an unknown principal is [MembershipAnswer.Unknown], never "no memberships", and is asked again
     * rather than cached. Through real Redis and CoCache's async view: the fill's failure for it comes out of the cache
     * as an unknown principal (a 401 in the pipeline), never as a failed lookup (a 503).
     */
    @Test
    fun anUnknownPrincipalIsUnknownAndNotCached() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val unknown = CoCacheMembershipProvider(instanceA.membershipCache) { _, _ ->
            asked.incrementAndGet()
            MembershipAnswer.Unknown
        }
        unknown.memberships(tenant, alice).assert().isSameAs(MembershipAnswer.Unknown)
        unknown.memberships(tenant, alice).assert().isSameAs(MembershipAnswer.Unknown)
        asked.get().assert().isEqualTo(2)
        // As the pipeline resolves it: an unknown principal refuses the credential (null), it does not fail (503).
        MembershipResolver(unknown).resolve(tenant, alice, MembershipSource.Provider.UNCAPPED).assert().isNull()
    }

    /**
     * A loader that never answers fails its fill within the fill timeout, so the principal is not unavailable on this
     * instance for good: the next request starts a new fill, which can answer.
     */
    @Test
    fun aLoaderThatNeverAnswersFailsItsFillAndTheNextRequestRetries() = runBlocking<Unit> {
        val hang = java.util.concurrent.atomic.AtomicBoolean(true)
        val provider = CoCacheMembershipProvider(instanceA.membershipCache, 100.milliseconds) { _, _ ->
            if (hang.get()) awaitCancellation()
            loads.incrementAndGet()
            MembershipAnswer.Known(stored)
        }
        val failure = assertThrows<Exception> { runBlocking { provider.memberships(tenant, alice) } }
        generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains("did not answer within") }
            .assert().isTrue()
        hang.set(false)
        provider.memberships(tenant, alice).known().assertSameAs(stored)
    }

    /** A deactivated principal is cached like any other answer, and every instance reads it as inactive. */
    @Test
    fun anInactivePrincipalIsCachedAsInactive() = runBlocking<Unit> {
        val inactive = Memberships(Memberships.Source.PROVIDER, stored.roles, stored.attachments, active = false)
        CoCacheMembershipProvider(instanceA.membershipCache) { _, _ -> MembershipAnswer.Known(inactive) }
            .memberships(tenant, alice).known().active.assert().isFalse()
        CoCacheMembershipProvider(instanceB.membershipCache) { _, _ -> error("not asked") }.memberships(tenant, alice)
            .known().active.assert().isFalse()
    }

    @Test
    fun aFailingLoaderThrowsAndCachesNothing() = runBlocking<Unit> {
        val failing = CoCacheMembershipProvider(instanceA.membershipCache) { _, _ -> error("directory down") }
        assertThrows<IllegalStateException> { runBlocking { failing.memberships(tenant, alice) } }
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun aLoaderAnsweringClaimsIsRefused() = runBlocking<Unit> {
        val wrong = CoCacheMembershipProvider(instanceA.membershipCache) { _, _ ->
            MembershipAnswer.Known(MembershipClaims.parse(listOf("admin"), emptyList()))
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
