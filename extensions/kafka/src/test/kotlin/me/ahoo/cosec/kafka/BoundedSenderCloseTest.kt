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

package me.ahoo.cosec.kafka

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * A send the sender drops when it is closed ends (#1394): its answer fails, saying the sender was closed, so a caller
 * waiting for it and its failure callback get an outcome, and it leaves the unanswered. One handed to the broker is
 * not undone.
 */
class BoundedSenderCloseTest {
    private val sender = BoundedSender("Kafka audit queue of [t]", "bounded-sender-test", 1, 50.milliseconds)

    /** A first send that holds the only thread until [release], so the second stays queued. */
    private class Blocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun send(): CompletableFuture<*> {
            started.countDown()
            release.await()
            return CompletableFuture.completedFuture("sent")
        }
    }

    @Test
    fun `a send dropped on close ends, fails, runs its callback and leaves the unanswered`() {
        val first = Blocking()
        val secondStarted = AtomicInteger()
        val firstQueued = sender.send(first::send)
        first.started.await(5, TimeUnit.SECONDS).assert().isTrue()
        val second = sender.send {
            secondStarted.incrementAndGet()
            CompletableFuture.completedFuture("sent")
        }
        val callback = CompletableFuture<Throwable?>()
        second.sent.whenComplete { _, error -> callback.complete(error) }

        sender.close()

        // The thread held by the first send was interrupted at the bound; the second was never started.
        secondStarted.get().assert().isEqualTo(0)
        second.sent.isCompletedExceptionally.assert().isTrue()
        callback.get(5, TimeUnit.SECONDS).assert().isNotNull()
        assertThrows<IllegalStateException> { runBlocking { second.await() } }.message.assert()
            .contains("was closed before this record was sent").contains("Kafka audit queue of [t]")
        // The one in progress was interrupted, as before: it ends with that, and is no longer unanswered.
        assertThrows<java.util.concurrent.ExecutionException> { firstQueued.sent.get(5, TimeUnit.SECONDS) }
        sender.unanswered.assert().isEqualTo(0)
    }

    /** The close itself interrupted while it waits: the same bound is applied at once, and the flag is kept. */
    @Test
    fun `a close that is interrupted drops the queued sends too`() {
        val first = Blocking()
        sender.send(first::send)
        first.started.await(5, TimeUnit.SECONDS).assert().isTrue()
        val second = sender.send { CompletableFuture.completedFuture("sent") }
        var interrupted = false
        val closer = Thread {
            Thread.currentThread().interrupt()
            sender.close()
            interrupted = Thread.currentThread().isInterrupted
        }

        closer.start()
        closer.join(5_000)

        second.sent.isCompletedExceptionally.assert().isTrue()
        interrupted.assert().isTrue()
        sender.unanswered.assert().isEqualTo(0)
    }

    /** The control: a close that drains drops nothing, and the send ends with the broker's answer. */
    @Test
    fun `a send that finishes before the close is answered, not dropped`() {
        val queued = sender.send { CompletableFuture.completedFuture("sent") }

        sender.close()

        queued.sent.get(5, TimeUnit.SECONDS).assert().isEqualTo("sent")
        runBlocking { queued.await() }
        sender.unanswered.assert().isEqualTo(0)
    }

    @Test
    fun `a send whose caller stopped waiting is not made`() {
        val first = Blocking()
        sender.send(first::send)
        first.started.await(5, TimeUnit.SECONDS).assert().isTrue()
        val made = AtomicInteger()
        val second = sender.send {
            made.incrementAndGet()
            CompletableFuture.completedFuture("sent")
        }
        val waiting = Thread { runCatching { runBlocking { kotlinx.coroutines.withTimeout(20) { second.await() } } } }
        waiting.start()
        waiting.join(5_000)

        first.release.countDown()
        sender.close()

        made.get().assert().isEqualTo(0)
    }

    @Test
    fun `a full queue fails at once`() {
        val first = Blocking()
        sender.send(first::send)
        first.started.await(5, TimeUnit.SECONDS).assert().isTrue()
        sender.send { CompletableFuture.completedFuture("sent") }

        assertThrows<IllegalStateException> { sender.send { CompletableFuture.completedFuture("sent") } }.message
            .assert().contains("is full")

        first.release.countDown()
        sender.close()
    }
}
