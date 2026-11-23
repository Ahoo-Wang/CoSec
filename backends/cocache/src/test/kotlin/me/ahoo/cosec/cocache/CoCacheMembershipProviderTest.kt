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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.stage.MembershipResolver
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * RFC 0006 §5.5.2, `MEM-2`, `MEM-5`: each instance holds what its own loader answered, and nothing that goes wrong reads
 * as "no memberships". Real Redis only where a test needs what crosses it: the invalidation frames, and nothing else.
 */
class CoCacheMembershipProviderTest {
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

    private fun local() = LocalMemberships(LocalMemberships.DEFAULT_TTL, MAX_ENTRIES)

    private val providerA = CoCacheMembershipProvider(local(), loader = loader)

    private fun MembershipAnswer.known(): Memberships = (this as MembershipAnswer.Known).memberships

    private fun Memberships.assertSameAs(expected: Memberships) {
        source.assert().isEqualTo(expected.source)
        roles.assert().isEqualTo(expected.roles)
        attachments.assert().isEqualTo(expected.attachments)
    }

    @Test
    fun aMissLoadsOnceAndTheNextRequestsAnswerFromThisInstance() = runBlocking<Unit> {
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        loads.get().assert().isEqualTo(1)
        // Another instance asks its own loader: nothing is shared.
        CoCacheMembershipProvider(local(), loader = loader).memberships(tenant, alice).known().assertSameAs(stored)
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun anInvalidationLoadsAgain() = runBlocking<Unit> {
        providerA.memberships(tenant, alice)
        providerA.invalidate(tenant, alice)
        providerA.memberships(tenant, alice)
        loads.get().assert().isEqualTo(2)
    }

    /** A failed load throws, the resolver turns it into a failure, and the next request loads again (`MEM-2`). */
    @Test
    fun aFailedLoadIsNeverNoMemberships() = runBlocking<Unit> {
        val failing = CoCacheMembershipProvider(local()) { _, _ -> error("directory down") }
        assertThrows<IllegalStateException> { runBlocking { failing.memberships(tenant, alice) } }
        val failure = assertThrows<MembershipUnavailableException> {
            runBlocking { MembershipResolver(failing).resolve(tenant, alice, MembershipSource.Provider.UNCAPPED) }
        }
        failure.message.assert().contains("failed")
    }

    /** A principal or tenant id with an unpaired surrogate names no key (`IDN-10`): an error, and the loader is not asked. */
    @Test
    fun aKeyThatCannotBeWrittenIsAnErrorNeverNoMemberships() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                providerA.memberships(
                    tenant,
                    PrincipalId("alice\uD800")
                )
            }
        }
        assertThrows<IllegalArgumentException> { runBlocking { providerA.memberships(TenantId("acme\uDFFF"), alice) } }
        loads.get().assert().isEqualTo(0)
    }

    /** RFC 0006 §5.5.2: the request after a load is answered on the caller's thread, without suspending. */
    @Test
    fun theRequestAfterALoadIsALocalHit() = runBlocking<Unit> {
        providerA.memberships(tenant, alice).known().assertSameAs(stored)
        val next = CompletableFuture<MembershipAnswer>()
        val call: suspend () -> MembershipAnswer = { providerA.memberships(tenant, alice) }
        call.startCoroutine(Continuation(EmptyCoroutineContext) { next.complete(it.getOrThrow()) })
        next.isDone.assert().describedAs("the request after the load suspended").isTrue()
        loads.get().assert().isEqualTo(1)
    }

    /** RFC 0009 §4.2: an unknown principal is [MembershipAnswer.Unknown], never "no memberships", and not kept. */
    @Test
    fun anUnknownPrincipalIsUnknownAndNotCached() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val unknown = CoCacheMembershipProvider(local(), unknownLife = Duration.ZERO) { _, _ ->
            asked.incrementAndGet()
            MembershipAnswer.Unknown
        }
        unknown.memberships(tenant, alice).assert().isSameAs(MembershipAnswer.Unknown)
        unknown.memberships(tenant, alice).assert().isSameAs(MembershipAnswer.Unknown)
        asked.get().assert().isEqualTo(2)
        MembershipResolver(unknown).resolve(tenant, alice, MembershipSource.Provider.UNCAPPED).assert().isNull()
    }

    /**
     * R2C-18: the loader's "unknown" is remembered for a short life; `invalidate` drops it at once, the end of the life
     * does too, and a failed load is never remembered as "unknown".
     */
    @Test
    fun anUnknownPrincipalIsRememberedBrieflyAndNeverOnAFailure() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val answers = java.util.ArrayDeque<() -> MembershipAnswer>()
        val provider = CoCacheMembershipProvider(local(), unknownLife = Duration.ofMillis(400)) { _, _ ->
            asked.incrementAndGet()
            answers.removeFirst()()
        }
        answers.add { MembershipAnswer.Unknown }
        repeat(3) { provider.memberships(tenant, alice).assert().isSameAs(MembershipAnswer.Unknown) }
        asked.get().assert().describedAs("three requests, one load").isEqualTo(1)

        answers.add { MembershipAnswer.Known(stored) }
        provider.invalidate(tenant, alice)
        provider.memberships(tenant, alice).known().assertSameAs(stored)
        asked.get().assert().isEqualTo(2)

        val other = PrincipalId("bob")
        answers.add { MembershipAnswer.Unknown }
        provider.memberships(tenant, other).assert().isSameAs(MembershipAnswer.Unknown)
        Thread.sleep(500)
        answers.add { throw MembershipUnavailableException("the directory is down") }
        assertThrows<MembershipUnavailableException> { provider.memberships(tenant, other) }
        asked.get().assert().isEqualTo(4)
        answers.add { MembershipAnswer.Known(stored) }
        provider.memberships(tenant, other).known().assertSameAs(stored)
    }

    /** A loader that never answers fails its fill within the fill timeout; the next request starts a new one. */
    @Test
    fun aLoaderThatNeverAnswersFailsItsFillAndTheNextRequestRetries() = runBlocking<Unit> {
        val hang = java.util.concurrent.atomic.AtomicBoolean(true)
        val provider = CoCacheMembershipProvider(local(), 100.milliseconds.toJavaDuration()) { _, _ ->
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

    @Test
    fun anInactivePrincipalIsCachedAsInactive() = runBlocking<Unit> {
        val inactive = Memberships(Memberships.Source.PROVIDER, stored.roles, stored.attachments, active = false)
        val provider = CoCacheMembershipProvider(local()) { _, _ -> MembershipAnswer.Known(inactive) }
        provider.memberships(tenant, alice).known().active.assert().isFalse()
        provider.memberships(tenant, alice).known().active.assert().isFalse()
    }

    @Test
    fun aLoaderAnsweringClaimsIsRefused() = runBlocking<Unit> {
        val wrong = CoCacheMembershipProvider(local()) { _, _ ->
            MembershipAnswer.Known(MembershipClaims.parse(listOf("admin"), emptyList()))
        }
        assertThrows<IllegalStateException> { runBlocking { wrong.memberships(tenant, alice) } }
    }

    /** What crosses the channel: one frame per invalidation, naming the key and nothing else. */
    @Test
    fun anInvalidationIsAFrameOfTheKeyOnly() = runBlocking<Unit> {
        val published = CopyOnWriteArrayList<ByteArray>()
        val channel = object : InvalidationChannel {
            override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription =
                object : Subscription {
                    override fun close() = Unit
                }

            override suspend fun publish(keyspace: Keyspace, frame: ByteArray) {
                published += frame
            }
        }
        val provider = CoCacheMembershipProvider(
            local(),
            invalidations = MembershipInvalidations(channel, MembershipInvalidations.keyspace("test")),
            loader = loader,
        )
        provider.invalidate(tenant, alice)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
        while (published.isEmpty()) {
            check(System.nanoTime() < deadline) { "No frame within $BOUND_SECONDS s." }
            Thread.onSpinWait()
        }
        me.ahoo.cocache.api.spi.InvalidationFrame.decode(published.single()).keys
            .assert().containsExactly("test:membership:" + CoCacheMembershipProvider.key(tenant, alice))
        provider.close()
    }

    private companion object {
        const val MAX_ENTRIES = 1_000

        /** How long a test waits for a frame it expects; it fails past it. */
        const val BOUND_SECONDS = 5L
    }
}
