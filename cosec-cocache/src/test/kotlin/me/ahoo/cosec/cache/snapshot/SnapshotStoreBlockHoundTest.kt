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

import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.snapshot.SnapshotChangeListener
import me.ahoo.cosec.snapshot.SnapshotCompiler
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotRead
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.blockhound.BlockHound
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * No blocking on the event loop (AGENTS.md, RFC 0003 §6): BlockHound watches threads named like a non-blocking caller,
 * and every store and publisher entry point, and a [SnapshotSource] cold load over the store, runs on such a thread
 * against real Redis. Only these threads are watched, so other tests in the JVM are unaffected.
 */
class SnapshotStoreBlockHoundTest {
    private fun policy(id: String) = PolicyData(id, "c", id, "", PolicyType.CUSTOM, "t")

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
    fun storeAndPublisherNeverBlockTheCaller() {
        RedisTarget.REDIS.backend().use { backend ->
            SnapshotNode(backend, newKeyPrefix()).use { node ->
                node.publisher.setPolicy(policy("seed")).block()
                val pending = onNonBlockingThread {
                    val listener = object : SnapshotChangeListener {
                        override fun onChanged(keys: Collection<SnapshotKey>) = Unit

                        override fun onReset() = Unit
                    }
                    val subscription = node.store.subscribe(listener)
                    listOf<CompletableFuture<*>>(
                        node.store.read(SnapshotKey.Policies),
                        node.store.read(SnapshotKey.App("absent")),
                        node.store.readIfChanged(SnapshotKey.Policies, null),
                        node.publisher.setPolicy(policy("a")).toFuture(),
                        node.publisher.setAppPermission(appPermission("app"))
                            .then(node.publisher.setRoleGrants("app", "r", "", setOf("p1")))
                            .toFuture(),
                    ) to subscription
                }
                pending.first.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                pending.second.close()
                // a second read answers from the local copy; its digest is computed off the watched thread
                onNonBlockingThread { node.store.read(SnapshotKey.Policies) }
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
                node.publisher.setPolicy(policy("a")).block()
                node.publisher.setAppPermission(appPermission("app")).block()
                SnapshotSource(
                    node.store,
                    SnapshotCompiler { _, document -> document.revision },
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

    companion object {
        private const val THREAD_PREFIX = "cosec-non-blocking-test"
        private const val TIMEOUT_SECONDS = 30L
        private val threads = AtomicInteger()
        private val violations = CopyOnWriteArrayList<String>()

        init {
            BlockHound.builder()
                .nonBlockingThreadPredicate { current -> current.or { it.name.startsWith(THREAD_PREFIX) } }
                // The tests log Lettuce at DEBUG to a console appender, which takes a lock: the logging setup, not
                // the code under test. Everything else on the watched threads must not block.
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "writeBytes")
                .blockingMethodCallback { method ->
                    violations += "${Thread.currentThread().name}: $method\n" +
                        Throwable().stackTrace.take(STACK_DEPTH).joinToString("\n") { "    at $it" }
                }
                .install()
        }

        private const val STACK_DEPTH = 40
    }
}
