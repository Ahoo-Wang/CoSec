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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Where both Kafka listeners make their sends: `KafkaOperations.send` may block while the producer waits for metadata or
 * buffer space, so it runs on a thread of its own, never on the caller's or a shared pool's, with at most
 * [queueCapacity] sends waiting. When they pile up because the broker is slow, a new one fails at once instead of
 * queueing without bound. A send whose caller stopped waiting (it timed out) before its turn is dropped, not sent.
 *
 * [close] stops taking sends and waits up to [drainTimeout] for those queued, then for the broker's answers to those
 * made; at the bound the queued sends are dropped and the one in progress is interrupted, and it says so.
 */
internal class BoundedSender(
    /** How messages name it: `Kafka audit queue of [<topic>]`. */
    private val queue: String,
    threadName: String,
    queueCapacity: Int,
    private val drainTimeout: Duration,
) {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity)) {
        Thread(it, threadName).apply { isDaemon = true }
    }

    /** The sends the broker has not answered yet: what [close] waits for once the queue is empty. */
    private val inFlight: MutableSet<CompletableFuture<*>> = ConcurrentHashMap.newKeySet()

    /**
     * Queues [send] and returns its answer, without waiting for it.
     *
     * @throws IllegalStateException when the queue is full
     */
    fun send(send: () -> CompletableFuture<*>): Queued {
        val queued = try {
            CompletableFuture.supplyAsync(send, executor)
        } catch (e: RejectedExecutionException) {
            throw IllegalStateException("The $queue is full.", e)
        }
        val sent: CompletableFuture<*> = queued.thenCompose { it }
        inFlight += sent
        sent.whenComplete { _, _ -> inFlight -= sent }
        return Queued(queued, sent)
    }

    /** One send: [sent] completes with the broker's answer. */
    internal class Queued(private val queued: CompletableFuture<*>, val sent: CompletableFuture<*>) {
        /** Waits for the broker's answer; a caller that stops waiting drops the send if it has not been made yet. */
        suspend fun await() {
            try {
                sent.await()
            } catch (e: CancellationException) {
                queued.cancel(false)
                throw e
            }
        }
    }

    /** Stops taking sends and drains them (see the class). A blocking call, for a host's shutdown. */
    fun close() {
        executor.shutdown()
        if (!drained()) {
            val dropped = executor.shutdownNow().size
            log.warn {
                "The $queue did not drain within $drainTimeout: $dropped queued records are dropped and " +
                    "${inFlight.size} sends are unanswered."
            }
        }
    }

    private fun drained(): Boolean {
        val deadline = System.nanoTime() + drainTimeout.inWholeNanoseconds
        return try {
            executor.awaitTermination(drainTimeout.inWholeNanoseconds, TimeUnit.NANOSECONDS) &&
                answered(deadline - System.nanoTime())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Whether the broker answered every send within [timeoutNanos]. A send that failed was answered. */
    private fun answered(timeoutNanos: Long): Boolean = try {
        CompletableFuture.allOf(*inFlight.toTypedArray()).get(timeoutNanos, TimeUnit.NANOSECONDS)
        true
    } catch (_: ExecutionException) {
        true
    } catch (_: TimeoutException) {
        false
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
