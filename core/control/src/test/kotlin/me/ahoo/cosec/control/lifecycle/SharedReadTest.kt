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
@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * DR1-F-10: the calls that find no fresh read wait for the one load in flight, its failure included, so each waits at
 * most one load (`CTL-82`'s bounded waits), never one per call queued ahead of it.
 */
class SharedReadTest {
    private val clock = MutableClock()
    private val loads = AtomicInteger()
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    /** A real store whose document list answers, after [release], with a failure: what a store timing out does. */
    private val store = object : ControlStore by InMemoryControlStore(clock) {
        override suspend fun documents(): Set<SnapshotKey> {
            loads.incrementAndGet()
            entered.complete(Unit)
            release.await()
            throw IOException("the store timed out")
        }
    }

    @Test
    fun `calls arriving while a load fails all fail with that one load - CTL-82`() = runBlocking<Unit> {
        val shared = SharedRead(clock) { store.documents() }
        val first = async { runCatching { shared.read() }.exceptionOrNull() }
        withTimeout(EVENT.toMillis()) { entered.await() }
        // Each runs until it waits: on the load in flight, or, as before, on the lock for a load of its own.
        val waiting = (1..WAITERS).map {
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { shared.read() }.exceptionOrNull() }
        }

        release.complete(Unit)
        val failures = (listOf(first) + waiting).map { withTimeout(EVENT.toMillis()) { it.await() } }

        loads.get().assert().isEqualTo(1)
        failures.forEach { it.assert().isInstanceOf(IOException::class.java) }
        // A failed read is never kept: the next call loads again (CTL-52).
        runCatching { shared.read() }.exceptionOrNull().assert().isInstanceOf(IOException::class.java)
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun `a call waiting on a load whose caller is cancelled loads again, and is not cancelled`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val shared = SharedRead(clock) {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                gate.await()
            }
            "documents"
        }
        val cancelled = launch { shared.read() }
        withTimeout(EVENT.toMillis()) { entered.await() }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { shared.read().value }

        cancelled.cancelAndJoin()

        withTimeout(EVENT.toMillis()) { waiter.await() }.assert().isEqualTo("documents")
        calls.get().assert().isEqualTo(2)
    }

    private companion object {
        const val WAITERS = 8

        /** How long a coroutine of this test may take to reach a point the test waits for. */
        val EVENT: Duration = Duration.ofSeconds(10)
    }
}
