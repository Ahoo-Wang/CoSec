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

package me.ahoo.cosec.e2e

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.blockhound.BlockHound
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * No blocking on the event loop (AGENTS.md): once the documents are installed, a request decides on the thread that
 * received it, without blocking and without suspending (RFC 0006 §7.2). BlockHound watches threads named like a
 * non-blocking caller; the allowances are those of `SnapshotStoreBlockHoundTest`.
 */
class SliceBlockHoundTest {
    @Test
    fun `blockhound watches the test threads`() {
        violations.clear()
        onNonBlockingThread { runCatching { Thread.sleep(1) } }
        violations.assert().isNotEmpty()
    }

    @Test
    fun `a warm request decides on its thread without blocking or suspending`() {
        Slice().use { slice ->
            slice.publish(
                SnapshotKey.Platform,
                ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*"}]}],"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]"""
            )
            slice.publish(SnapshotKey.Tenant("acme"), "")
            val request = request("GET", "/orders/1", "alice@acme")
            // Warm the source: the documents are compiled and installed before the measured call.
            runBlocking { slice.evaluate(request) }.proceed.assert().isTrue()
            violations.clear()
            val outcome = onNonBlockingThread {
                val result = CompletableFuture<Outcome>()
                val evaluate: suspend () -> Outcome = { slice.evaluate(request) }
                evaluate.startCoroutine(Continuation(EmptyCoroutineContext) { result.complete(it.getOrThrow()) })
                // Completed before startCoroutine returned: nothing suspended, nothing was dispatched.
                check(result.isDone) { "The warm request suspended." }
                result.join()
            }
            outcome.proceed.assert().isTrue()
            violations.assert().isEmpty()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> onNonBlockingThread(block: () -> T): T {
        val result = CompletableFuture<T>()
        Thread({
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }, "$THREAD_PREFIX-${threads.incrementAndGet()}").start()
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private const val THREAD_PREFIX = "cosec-e2e-non-blocking"
        private const val TIMEOUT_SECONDS = 30L
        private const val STACK_DEPTH = 40
        private val threads = AtomicInteger()
        private val violations = CopyOnWriteArrayList<String>()

        init {
            BlockHound.builder()
                .nonBlockingThreadPredicate { current -> current.or { it.name.startsWith(THREAD_PREFIX) } }
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "writeBytes")
                .allowBlockingCallsInside("java.util.concurrent.ScheduledThreadPoolExecutor\$DelayedWorkQueue", "offer")
                .blockingMethodCallback { method ->
                    violations += "${Thread.currentThread().name}: $method\n" +
                        Throwable().stackTrace.take(STACK_DEPTH).joinToString("\n") { "    at $it" }
                }
                .install()
        }
    }
}
