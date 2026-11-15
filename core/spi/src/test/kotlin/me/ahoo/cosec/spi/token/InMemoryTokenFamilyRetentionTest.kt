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

package me.ahoo.cosec.spi.token

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * A single-instance store must not keep every session it ever had (#1387): a family past its end is released, a bounded
 * number per call and without a thread, and never one that a concurrent replace has kept alive.
 */
class InMemoryTokenFamilyRetentionTest {
    private val start = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = AtomicReference(start)
    private val store = InMemoryTokenFamilyStore { clock.get() }

    private fun family(id: String, lives: Duration = Duration.ofSeconds(60), generation: Long = 1) = TokenFamily(
        id,
        TenantId("t"),
        PrincipalId("alice"),
        start,
        start.plus(lives),
        generation,
        null,
        false,
    )

    /** A family that signed in at the clock's present and lives a day. */
    private fun signedInNow(id: String): TokenFamily {
        val at = clock.get()
        return TokenFamily(id, TenantId("t"), PrincipalId("alice"), at, at.plus(Duration.ofDays(1)), 1, null, false)
    }

    @Test
    fun `expired sessions are released from the single instance store`() = runTest {
        repeat(10_000) { store.create(family("s-$it")).assert().isTrue() }
        store.size.assert().isEqualTo(10_000)
        clock.set(start.plus(Duration.ofHours(1)))

        repeat(10_000) { store.read("s-$it").assert().isNull() }
        store.create(signedInNow("live")).assert().isTrue()
        store.read("live").assert().isNotNull()

        store.size.assert().isEqualTo(1)
    }

    /** Families nobody reads again are released by the calls that follow, whichever ids those name. */
    @Test
    fun `a family nobody reads again is released by later calls`() = runTest {
        repeat(200) { store.create(family("old-$it")) }
        clock.set(start.plus(Duration.ofHours(1)))

        repeat(20) { store.read("another-$it") }

        store.size.assert().isLessThan(200)
        repeat(200) { store.read("another") }
        store.size.assert().isEqualTo(0)
    }

    /** One call adds a bounded amount of work: a pass over a large store is never paid by a single request. */
    @Test
    fun `one call releases a bounded number of families`() = runTest {
        repeat(1_000) { store.create(family("old-$it")) }
        clock.set(start.plus(Duration.ofHours(1)))

        store.read("another")

        store.size.assert().isBetween(900, 1_000)
    }

    /** The sweep takes the oldest end first, and stops at the first family still alive. */
    @Test
    fun `a family that has not ended is never released`() = runTest {
        store.create(family("short", Duration.ofSeconds(10)))
        store.create(family("long", Duration.ofDays(1)))
        clock.set(start.plusSeconds(30))

        repeat(5) { store.read("another") }

        store.size.assert().isEqualTo(1)
        store.read("long")!!.family.id.assert().isEqualTo("long")
    }

    /** A replace that moves the end later leaves a stale, earlier entry in the index: the sweep must not take it. */
    @Test
    fun `a family replaced by one that ends later is not released at its old end`() = runTest {
        store.create(family("f", Duration.ofSeconds(60)))
        clock.set(start.plusSeconds(30))
        val read = store.read("f")!!
        val later = family("f", Duration.ofDays(1), generation = 2)
        store.replace(read.version, later).assert().isTrue()

        clock.set(start.plusSeconds(120))
        repeat(5) { store.read("another") }

        store.read("f")!!.family.generation.assert().isEqualTo(2)
        store.size.assert().isEqualTo(1)
    }

    @Test
    fun `a family created again over an ended one lives past the old end`() = runTest {
        store.create(family("f", Duration.ofSeconds(60))).assert().isTrue()
        clock.set(start.plusSeconds(120))
        val again = TokenFamily(
            "f",
            TenantId("t"),
            PrincipalId("alice"),
            clock.get(),
            clock.get().plusSeconds(600),
            1,
            null,
            false
        )
        store.create(again).assert().isTrue()

        repeat(5) { store.read("another") }

        store.read("f")!!.family.expiresAt.assert().isEqualTo(clock.get().plusSeconds(600))
    }

    /** Creating, replacing and reading from many threads while the clock moves loses no family that has not ended. */
    @Test
    fun `concurrent writers lose no live family`() = runBlocking {
        val sessions = 8
        val rotations = 300
        val churn = async(Dispatchers.Default) {
            repeat(2_000) {
                store.create(family("churn-$it", Duration.ofMillis(1)))
                clock.updateAndGet { at -> at.plusMillis(1) }
                store.read("churn-${it / 2}")
            }
        }
        val owners = (0 until sessions).map { n ->
            async(Dispatchers.Default) {
                val id = "owner-$n"
                store.create(family(id, Duration.ofDays(1))).assert().isTrue()
                repeat(rotations) {
                    var swapped = false
                    while (!swapped) {
                        val read = checkNotNull(store.read(id)) { "[$id] was released while it lived" }
                        swapped = store.replace(read.version, read.family.rotated(clock.get()))
                    }
                }
            }
        }
        (owners + churn).awaitAll()

        repeat(sessions) {
            store.read("owner-$it")!!.family.generation.assert().isEqualTo(rotations + 1L)
        }
    }
}
