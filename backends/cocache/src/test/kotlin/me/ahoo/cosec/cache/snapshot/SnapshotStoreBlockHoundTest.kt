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

package me.ahoo.cosec.cache.snapshot

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.cache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.cache.limiter.ReactiveRedisRateLimiterTest
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.runtime.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.blockhound.BlockHound
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * No blocking on the event loop (AGENTS.md, RFC 0003 §6): BlockHound watches threads named like a non-blocking caller,
 * and every store entry point, a writer publishing through it, and a [SnapshotSource] cold load over the store, runs
 * on such a thread against real Redis. Only these threads are watched, so other tests in the JVM are unaffected.
 *
 * Two waits are allowed, each exactly where it happens:
 * - logback's console appender lock: the logging setup of the test JVMs, not the code under test;
 * - `ScheduledThreadPoolExecutor$DelayedWorkQueue.offer`: a cold load arms its timeout on the caller's thread, and
 *   scheduling a delayed task takes the queue's lock for one heap insertion, never across I/O. Every JDK way to schedule
 *   a delayed task (including handing it to the scheduler thread, which offers to the same kind of queue) takes such a
 *   lock, Reactor's own timers on an event loop included, so it is a bounded wait, not blocking I/O (RFC 0003 §5.3).
 *   Without the allowance, the test failed only when the lock happened to be contended: flaky, not a finding.
 */
class SnapshotStoreBlockHoundTest {
    /** Runs [block] on a fresh watched thread and returns its result. */
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

    @BeforeEach
    fun clear() {
        violations.clear()
    }

    @Test
    fun blockHoundWatchesTheTestThreads() {
        onNonBlockingThread { runCatching { Thread.sleep(1) } }
        violations.assert().isNotEmpty()
    }

    @Test
    fun theStoreAndItsWritersNeverBlockTheCaller() {
        RedisTarget.REDIS.backend().use { backend ->
            SnapshotNode(backend, newKeyPrefix()).use { node ->
                node.writer.publish(SnapshotKey.Platform).block()
                val pending = onNonBlockingThread {
                    val listener = object : SnapshotChangeListener {
                        override fun onChanged(keys: Collection<SnapshotKey>) = Unit

                        override fun onReset() = Unit
                    }
                    val subscription = node.store.subscribe(listener)
                    listOf<CompletableFuture<*>>(
                        node.store.read(SnapshotKey.Platform),
                        node.store.read(SnapshotKey.App("absent")),
                        node.store.readIfChanged(SnapshotKey.Platform, null),
                        node.writer.publish(SnapshotKey.Platform).toFuture(),
                        node.writer.publish(SnapshotKey.App("app"))
                            .then(node.writer.publish(SnapshotKey.App("app")))
                            .toFuture(),
                    ) to subscription
                }
                pending.first.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                pending.second.close()
                // a second read answers from the local copy; its digest is computed off the watched thread
                onNonBlockingThread { node.store.read(SnapshotKey.Platform) }
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .assert().isInstanceOf(SnapshotRead.Present::class.java)
            }
        }
        violations.assert().isEmpty()
    }

    @Test
    fun aColdLoadOverTheStoreNeverBlocksTheCaller() {
        RedisTarget.REDIS.backend().use { backend ->
            SnapshotNode(backend, newKeyPrefix()).use { node ->
                node.writer.publish(SnapshotKey.Platform).block()
                node.writer.publish(SnapshotKey.App("app")).block()
                DocumentSnapshots.source(
                    node.store,
                    SnapshotCompiler { _, document -> document.revision },
                    SnapshotOptions(revalidateInterval = Duration.ZERO),
                ).use { source ->
                    val loading = onNonBlockingThread { source.load("app") }
                    loading.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).version.scoped.assert().isEqualTo(1L)
                    onNonBlockingThread { source.peek("app") }.assert().isNotNull()
                }
            }
        }
        violations.assert().isEmpty()
    }

    /** RFC 0006 §8.4: the suspending Redis rate limiter awaits Redis; the caller's thread never waits for it. */
    @Test
    fun theSuspendingRedisRateLimiterNeverBlocksTheCaller() {
        val factory = ReactiveRedisRateLimiterTest.connectionFactory("localhost", 6379)
        try {
            val limiter =
                ReactiveRedisRateLimiter(ReactiveStringRedisTemplate(factory), "cosec:test:rate:${newKeyPrefix()}")
            // Warm the connection, so the watched call measures the request alone.
            runBlocking { limiter.tryAcquire("rule", "global", RateLimitSpec(10.0)) }
            violations.clear()
            val pending = onNonBlockingThread {
                val result = CompletableFuture<Boolean>()
                val call: suspend () -> Boolean = { limiter.tryAcquire("rule", "global", RateLimitSpec(10.0)).allowed }
                call.startCoroutine(Continuation(EmptyCoroutineContext) { result.complete(it.getOrThrow()) })
                result
            }
            pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            factory.destroy()
        }
        violations.assert().isEmpty()
    }

    companion object {
        private const val THREAD_PREFIX = "cosec-non-blocking-test"
        private const val TIMEOUT_SECONDS = 30L
        private val threads = AtomicInteger()
        private val violations = CopyOnWriteArrayList<String>()

        init {
            BlockHound.builder()
                .nonBlockingThreadPredicate { current -> current.or { it.name.startsWith(THREAD_PREFIX) } }
                // The test JVMs log with config/logback.xml (the root build sets it): the root logger at TRACE on a
                // console appender, which takes a lock, so Lettuce's command logging can wait on it. That is the
                // logging setup, not the code under test; everything else on the watched threads must not block.
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "writeBytes")
                // A bounded lock around one heap insertion when a timeout is armed (see the class comment).
                .allowBlockingCallsInside("java.util.concurrent.ScheduledThreadPoolExecutor\$DelayedWorkQueue", "offer")
                .blockingMethodCallback { method ->
                    violations += "${Thread.currentThread().name}: $method\n" +
                        Throwable().stackTrace.take(STACK_DEPTH).joinToString("\n") { "    at $it" }
                }
                .install()
        }

        private const val STACK_DEPTH = 40
    }
}
