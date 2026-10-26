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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

/** Threads for the T1–T3 tests (RFC 0006 §7.2): an event loop to start on, a client library's I/O thread. */
object ThreadingFixtures {
    const val EVENT_LOOP = "test-event-loop"
    const val CLIENT_IO = "test-client-io"

    fun singleThread(name: String): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name) }

    /** Counts the dispatches it is asked for, then hands them to [delegate]. */
    class CountingDispatcher(private val delegate: CoroutineDispatcher = Dispatchers.Default) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    /** A value [client] completes on its own thread, as a Lettuce or Kafka client would. */
    fun <T> completedOn(client: ExecutorService, value: T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({
            Thread.sleep(20)
            value
        }, client)

    /**
     * Starts [block] on [thread] the way the WebFlux bridge does (RFC 0006 §7.3): `Dispatchers.Unconfined`, so the start
     * dispatches nothing, plus [context]. Returns the result and the thread it finished on.
     */
    fun <T> startOn(thread: ExecutorService, context: CoroutineContext, block: suspend () -> T): Pair<T, Thread> {
        val done = CompletableFuture<Pair<T, Thread>>()
        thread.execute {
            val body: suspend () -> Pair<T, Thread> = { block() to Thread.currentThread() }
            body.startCoroutine(
                Continuation(
                    Dispatchers.Unconfined + context
                ) { result -> result.fold(done::complete, done::completeExceptionally) }
            )
        }
        return done.get(10, TimeUnit.SECONDS)
    }
}
