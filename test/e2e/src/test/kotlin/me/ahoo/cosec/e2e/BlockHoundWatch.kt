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

import reactor.blockhound.BlockHound
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one BlockHound installation of this module: BlockHound installs once per JVM, so every test that watches for
 * blocking shares it. It watches threads named [THREAD_PREFIX]; the allowances are those of
 * `SnapshotStoreBlockHoundTest`.
 */
object BlockHoundWatch {
    const val THREAD_PREFIX = "cosec-e2e-non-blocking"
    const val TIMEOUT_SECONDS = 30L
    private const val STACK_DEPTH = 40
    private val threads = AtomicInteger()

    /** The blocking calls seen on watched threads since the last [clear]. */
    val violations = CopyOnWriteArrayList<String>()

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

    fun clear() {
        violations.clear()
    }

    /** Runs [block] on a new watched thread and waits for it. */
    @Suppress("TooGenericExceptionCaught")
    fun <T> onNonBlockingThread(block: () -> T): T {
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
}
