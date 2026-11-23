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

import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `MEM-5`, DR1-D-2: on real Redis, two instances of one deployment hold their loaders' answers in their own memory. No
 * `<s>:membership:*` key is ever written, a membership record someone wrote there (an 8.0 preview's cache, or anyone with
 * a Redis credential) is never read as an answer, and an invalidation on one instance reaches the other by the channel.
 */
class MembershipsStayLocalTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val instanceA = CoSecTestCaches(redis, keyPrefix)

    // A backend of its own, as another process has: a backend does not hear its own frames back.
    private val redisB = CoSecTestCaches.redisBackend()
    private val instanceB = CoSecTestCaches(redisB, keyPrefix)
    private val client = RedisClient.create("redis://localhost:6379")
    private val commands = client.connect().sync()
    private val tenant = TenantId("acme")
    private val alice = PrincipalId("alice")
    private val loaded = Memberships(
        Memberships.Source.PROVIDER,
        mapOf(SpaceId.DEFAULT to setOf(RoleId("viewer"))),
        emptyList()
    )
    private val loads = AtomicInteger()
    private val loader = MembershipLoader { _, _ ->
        loads.incrementAndGet()
        MembershipAnswer.Known(loaded)
    }

    private fun provider(caches: CoSecTestCaches) = CoCacheMembershipProvider(
        LocalMemberships(LocalMemberships.DEFAULT_TTL, MAX_ENTRIES),
        invalidations = caches.factory.membershipInvalidations(keyPrefix),
        loader = loader,
    )

    private val providerA = provider(instanceA)
    private val providerB = provider(instanceB)

    @AfterEach
    fun close() {
        providerA.close()
        providerB.close()
        instanceA.close()
        instanceB.close()
        client.shutdown()
        redis.close()
        redisB.close()
    }

    private fun membershipKeys(): List<String> = commands.keys("${CoSecCacheSpecs.membershipPath(keyPrefix)}*")

    private fun MembershipAnswer.roles() = (this as MembershipAnswer.Known).memberships.roles

    @Test
    fun aRecordInRedisIsNeverReadAndNoneIsWritten() = runBlocking<Unit> {
        // What an 8.0 preview's shared cache stored, or what a holder of a data-plane credential could write.
        val seeded = "${CoSecCacheSpecs.membershipPath(keyPrefix)}:${CoCacheMembershipProvider.key(tenant, alice)}"
        commands.set(seeded, """{"roles":{"":["admin"]},"attachments":[]}""")
        providerA.memberships(tenant, alice).roles().assert().isEqualTo(loaded.roles)
        providerB.memberships(tenant, alice).roles().assert().isEqualTo(loaded.roles)
        loads.get().assert().describedAs("each instance asks its own loader").isEqualTo(2)
        membershipKeys().assert().containsExactly(seeded)
        commands.del(seeded)
        providerA.invalidate(tenant, PrincipalId("bob"))
        providerA.memberships(tenant, PrincipalId("bob"))
        membershipKeys().assert().isEmpty()
    }

    @Test
    fun anInvalidationOnOneInstanceReachesTheOther() = runBlocking<Unit> {
        providerB.memberships(tenant, alice)
        providerB.memberships(tenant, alice)
        loads.get().assert().isEqualTo(1)
        providerA.invalidate(tenant, alice)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FRAME_BOUND_SECONDS)
        while (true) {
            providerB.memberships(tenant, alice)
            if (loads.get() == 2) break
            check(
                System.nanoTime() < deadline
            ) { "The frame did not reach the other instance within $FRAME_BOUND_SECONDS s." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 1_000

        /** How long a frame may take from one instance to another on a local Redis; the test fails past it. */
        const val FRAME_BOUND_SECONDS = 5L
        const val POLL_MILLIS = 20L
    }
}
