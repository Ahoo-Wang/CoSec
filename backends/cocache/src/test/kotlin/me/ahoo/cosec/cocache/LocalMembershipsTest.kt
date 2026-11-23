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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** [LocalMemberships]: one load per key at a time, kept for its lifetime, never a failure, never past its bound. */
class LocalMembershipsTest {
    private val now = AtomicLong()
    private val memberships = Memberships(
        Memberships.Source.PROVIDER,
        mapOf(SpaceId.DEFAULT to setOf(RoleId("a"))),
        emptyList()
    )
    private val cache = LocalMemberships(Duration.ofSeconds(60), 3) { now.get() }
    private val loads = AtomicInteger()

    private fun loadNow(): CompletableFuture<Memberships> {
        loads.incrementAndGet()
        return CompletableFuture.completedFuture(memberships)
    }

    @Test
    fun `concurrent misses of one key share one load, and it answers them all`() {
        val pending = CompletableFuture<Memberships>()
        val first = cache.get("k") {
            loads.incrementAndGet()
            pending
        }
        val second = cache.get("k", ::loadNow)
        loads.get().assert().isEqualTo(1)
        cache.peek("k").assert().isNull()
        pending.complete(memberships)
        first.join().assert().isSameAs(memberships)
        second.join().assert().isSameAs(memberships)
        cache.peek("k").assert().isSameAs(memberships)
    }

    @Test
    fun `an entry lives its lifetime from its answer, and loads again after`() {
        cache.get("k", ::loadNow).join()
        now.addAndGet(Duration.ofSeconds(59).toNanos())
        cache.peek("k").assert().isSameAs(memberships)
        now.addAndGet(Duration.ofSeconds(1).toNanos())
        cache.peek("k").assert().isNull()
        cache.get("k", ::loadNow).join()
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun `a failed load is not kept, and the next one starts`() {
        val failed = cache.get("k") { CompletableFuture.failedFuture(IllegalStateException("down")) }
        failed.isCompletedExceptionally.assert().isTrue()
        cache.peek("k").assert().isNull()
        cache.get("k") { throw IllegalStateException("thrown") }.isCompletedExceptionally.assert().isTrue()
        cache.get("k", ::loadNow).join().assert().isSameAs(memberships)
    }

    @Test
    fun `an invalidation during a load answers its waiters and keeps nothing`() {
        val pending = CompletableFuture<Memberships>()
        val waiting = cache.get("k") { pending }
        cache.invalidate("k")
        pending.complete(memberships)
        waiting.join().assert().isSameAs(memberships)
        cache.peek("k").assert().isNull()
    }

    @Test
    fun `past its bound it drops the expired first, then any, and holds no more than the bound`() {
        cache.get("a", ::loadNow).join()
        now.addAndGet(Duration.ofSeconds(61).toNanos())
        cache.get("b", ::loadNow).join()
        cache.get("c", ::loadNow).join()
        cache.get("d", ::loadNow).join()
        cache.size.assert().isEqualTo(3)
        cache.peek("a").assert().isNull()
        cache.get("e", ::loadNow).join()
        cache.size.assert().isEqualTo(3)
        cache.peek("e").assert().isSameAs(memberships)
    }
}
