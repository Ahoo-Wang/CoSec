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

package me.ahoo.cosec.runtime.event

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OBS-3 and OBS-4, the BEST_EFFORT queues at their edges: a request never waits on a log line about them (#1392), and a
 * drain that gave up, or a cancel, starts no further listener call and accounts for every event it drops (#1393).
 */
class DecisionListenersShutdownTest {
    /** Holds its first call on [release]: a listener that writes synchronously to an output that is stuck. */
    private class Stuck(private val cooperative: Boolean = false) : DecisionListener {
        override val name = "stuck"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicInteger()

        override suspend fun onDecision(event: DecisionEvent) {
            if (started.count > 0) {
                started.countDown()
                if (cooperative) awaitCancellation() else release.await(10, TimeUnit.SECONDS)
            }
            completed.incrementAndGet()
        }
    }

    private fun pipeline(listeners: DecisionListeners): Pipeline = PipelineFixtures.pipeline { listeners(listeners) }

    private fun Pipeline.request(): Int = runBlocking { evaluate(PipelineFixtures.facts()).status }

    @Test
    fun `a full queue drops without waiting on the log, which is written off the request's thread`() {
        val listener = Stuck()
        val listeners = DecisionListeners(listOf(listener), queueCapacity = 1)
        val pipeline = pipeline(listeners)
        val logged = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val loggedOn = CopyOnWriteArrayList<String>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (event.formattedMessage.startsWith("Dropping decision events")) {
                    loggedOn += Thread.currentThread().name
                    logged.countDown()
                    unblock.await(10, TimeUnit.SECONDS)
                }
            }
        }.apply { start() }
        val logger = LoggerFactory.getLogger(DecisionListeners::class.java) as Logger
        logger.addAppender(appender)
        val loop = Executors.newSingleThreadExecutor { Thread(it, "test-event-loop") }
        try {
            pipeline.request()
            listener.started.await(5, TimeUnit.SECONDS).assert().isTrue()
            pipeline.request() // fills the queue
            pipeline.request() // dropped: the reporter now logs, and its appender is stuck
            logged.await(5, TimeUnit.SECONDS).assert().isTrue()

            val started = System.nanoTime()
            val status = runBlocking {
                withContext(
                    loop.asCoroutineDispatcher()
                ) { pipeline.evaluate(PipelineFixtures.facts()).status }
            }

            status.assert().isEqualTo(200)
            ((System.nanoTime() - started) / 1_000_000).assert().describedAs("ms on the request").isLessThan(200)
            loggedOn.assert().noneMatch { it == "test-event-loop" }
            listeners.stats.single().dropped.assert().isEqualTo(2)
        } finally {
            unblock.countDown()
            listener.release.countDown()
            logger.detachAppender(appender)
            loop.shutdownNow()
            listeners.cancel()
        }
    }

    @Test
    fun `after a drain gives up, only the call in flight finishes and the queued events are dropped`() {
        val listener = Stuck()
        val listeners = DecisionListeners(listOf(listener), drainTimeout = 50.milliseconds)
        val pipeline = pipeline(listeners)
        pipeline.request()
        listener.started.await(5, TimeUnit.SECONDS).assert().isTrue()
        pipeline.request()
        pipeline.request()

        listeners.drain().assert().isFalse()
        listener.completed.get().assert().isEqualTo(0)
        listener.release.countDown()

        Thread.sleep(200)
        listener.completed.get().assert().describedAs("calls finished after the drain gave up").isEqualTo(1)
        listeners.stats.single().dropped.assert().isEqualTo(2)
    }

    @Test
    fun `a cancelled consumer leaves nothing queued, and what it leaves is counted`() {
        val listener = Stuck(cooperative = true)
        val listeners = DecisionListeners(listOf(listener), drainTimeout = 50.milliseconds)
        val pipeline = pipeline(listeners)
        pipeline.request()
        listener.started.await(5, TimeUnit.SECONDS).assert().isTrue()
        pipeline.request()
        pipeline.request()

        listeners.drain().assert().isFalse()

        listeners.stats.single().dropped.assert().isEqualTo(2)
        listener.completed.get().assert().isEqualTo(0)
    }

    /**
     * The gap the check after the offer closes: a publish that found the queue open offers only after the listeners
     * stopped and emptied their queues. Its event is dropped and counted, never left queued for no one.
     */
    @Test
    fun `an event offered after the listeners stopped is dropped and counted, not left queued`() {
        val delivered = AtomicInteger()
        val listener = object : DecisionListener {
            override val name = "counting"

            override suspend fun onDecision(event: DecisionEvent) {
                delivered.incrementAndGet()
            }
        }
        val listeners = DecisionListeners(listOf(listener))
        val pipeline = pipeline(listeners)
        listeners.beforeOffer = {
            listeners.beforeOffer = null
            listeners.cancel()
        }

        pipeline.request()

        delivered.get().assert().isEqualTo(0)
        listeners.stats.single().dropped.assert().describedAs("the event offered after the stop").isEqualTo(1)
    }

    /** Requests published while the listeners drain, at full speed: every event is delivered or dropped, once. */
    @RepeatedTest(20)
    fun `events published while the listeners drain are each delivered or dropped, once`() {
        val delivered = AtomicInteger()
        val listener = object : DecisionListener {
            override val name = "counting"

            override suspend fun onDecision(event: DecisionEvent) {
                delivered.incrementAndGet()
            }
        }
        val listeners = DecisionListeners(listOf(listener), queueCapacity = 64, drainTimeout = 5.seconds)
        val pipeline = pipeline(listeners)
        val published = AtomicInteger()
        val go = CountDownLatch(1)
        val threads = (1..4).map {
            Thread {
                go.await()
                repeat(200) {
                    pipeline.request()
                    published.incrementAndGet()
                }
            }.apply { start() }
        }
        go.countDown()
        Thread.sleep(2)
        listeners.drain()
        threads.forEach { it.join() }

        (delivered.get() + listeners.stats.single().dropped).assert().isEqualTo(published.get().toLong())
    }
}
