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

package me.ahoo.cosec.spi.token

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * DR1-G-2 in one process: a record is kept no longer than its refresh token, from each write, and a principal keeps the
 * [TokenFamilyStore.SESSIONS_PER_PRINCIPAL] sessions that began last in a tenant, the earliest deleted.
 */
class InMemorySessionBoundTest {
    private val start = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = AtomicReference(start)
    private val refresh = Duration.ofDays(7)
    private val store = InMemoryTokenFamilyStore({ clock.get() }, refresh)

    private fun family(id: String, principal: String = "alice", tenant: String = "t") = TokenFamily(
        id,
        TenantId(tenant),
        PrincipalId(principal),
        clock.get(),
        clock.get().plus(Duration.ofDays(30)),
        1,
        null,
        false,
    )

    @Test
    fun `a record nobody refreshes ends with its refresh token, and each write keeps it that long again`() = runTest {
        store.create(family("f")).assert().isTrue()
        clock.set(start.plus(refresh).minusSeconds(1))
        val read = checkNotNull(store.read("f"))
        store.replace(read.version, read.family.rotated(clock.get())).assert().isTrue()
        clock.set(start.plus(refresh).plusSeconds(1))
        store.read("f").assert().isNotNull()
        clock.set(start.plus(refresh).plus(refresh))
        store.read("f").assert().isNull()
    }

    @Test
    fun `a write never keeps a record past the end of its session`() = runTest {
        val short =
            TokenFamily("s", TenantId("t"), PrincipalId("alice"), start, start.plus(Duration.ofDays(1)), 1, null, false)
        store.create(short).assert().isTrue()
        clock.set(start.plus(Duration.ofDays(1)))
        store.read("s").assert().isNull()
    }

    @Test
    fun `without a refresh validity a record lives to the end of its session`() = runTest {
        val unbounded = InMemoryTokenFamilyStore { clock.get() }
        unbounded.create(family("f")).assert().isTrue()
        clock.set(start.plus(Duration.ofDays(29)))
        unbounded.read("f").assert().isNotNull()
    }

    @Test
    fun `a principal keeps the sessions that began last, the earliest deleted, revoked or not`() = runTest {
        val limit = TokenFamilyStore.SESSIONS_PER_PRINCIPAL
        store.create(family("first")).assert().isTrue()
        store.create(family("revoked")).assert().isTrue()
        val read = checkNotNull(store.read("revoked"))
        store.replace(read.version, read.family.revoked()).assert().isTrue()
        (3..limit).forEach { store.create(family("s-$it")).assert().isTrue() }
        store.read("first").assert().isNotNull()

        store.create(family("next")).assert().isTrue()
        store.read("first").assert().isNull()
        store.read("revoked").assert().isNotNull()

        store.create(family("after")).assert().isTrue()
        store.read("revoked").assert().isNull()
        ((3..limit).map { "s-$it" } + "next" + "after").forEach { store.read(it).assert().describedAs(it).isNotNull() }
        store.size.assert().isEqualTo(limit)
    }

    @Test
    fun `the bound is per principal and per tenant`() = runTest {
        store.create(family("bob", principal = "bob")).assert().isTrue()
        store.create(family("elsewhere", tenant = "other")).assert().isTrue()
        repeat(TokenFamilyStore.SESSIONS_PER_PRINCIPAL + 1) { store.create(family("a-$it")).assert().isTrue() }
        store.read("bob").assert().isNotNull()
        store.read("elsewhere").assert().isNotNull()
    }

    /**
     * The whole structure stays bounded under a principal that signs in again and again, and a session refreshed again
     * and again: no more families than the bound, no more ends than families, one release time per principal.
     */
    @Test
    fun `logins and refreshes in a loop hold a bounded store`() = runTest {
        val index = InMemorySessionIndex { clock.get() }
        repeat(10_000) {
            clock.set(clock.get().plusSeconds(1))
            store.create(family("login-$it")).assert().isTrue()
            index.admit(family("i-$it"), clock.get(), Duration.ofDays(30), TokenFamilyStore.SESSIONS_PER_PRINCIPAL)
        }
        store.size.assert().isLessThanOrEqualTo(TokenFamilyStore.SESSIONS_PER_PRINCIPAL)
        store.endCount.assert().isLessThanOrEqualTo(store.size)
        index.owners.assert().isEqualTo(1)
        index.releaseTimes.assert().isEqualTo(1)

        val session = family("refreshed", principal = "bob")
        store.create(session).assert().isTrue()
        var read = checkNotNull(store.read(session.id))
        repeat(10_000) {
            clock.set(clock.get().plusSeconds(1))
            store.replace(read.version, read.family.rotated(clock.get())).assert().isTrue()
            read = checkNotNull(store.read(session.id))
        }
        store.endCount.assert().isLessThanOrEqualTo(store.size)
    }

    @Test
    fun `a principal's count is released once it has outlived its sessions`() = runTest {
        val index = InMemorySessionIndex { clock.get() }
        repeat(100) { index.admit(family("f-$it", principal = "p-$it"), clock.get(), Duration.ofMinutes(1), 64) }
        index.owners.assert().isEqualTo(100)
        clock.set(start.plus(Duration.ofMinutes(2)))
        repeat(10) { index.admit(family("g-$it", principal = "q-$it"), clock.get(), Duration.ofMinutes(1), 64) }
        index.owners.assert().isLessThan(110)
    }
}
