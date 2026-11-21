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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.audit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.captured
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * #1535: a listener that is handed an event off the caller and times out, or throws a cancellation of its own, lost
 * its record without a word: `later` rethrew every [CancellationException]. Only the host's cancellation, of the scope
 * that delivers, ends a delivery quietly; the rest is a delivery failure like any other, and is logged.
 */
class ChangeListenerTimeoutTest {
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val opened = ArrayList<ChangeListeners>()

    @BeforeEach
    fun capture() {
        logger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        logger.detachAppender(appender)
        opened.forEach { it.cancel() }
    }

    private fun warnings(): List<ILoggingEvent> =
        appender.captured().filter { it.level == Level.WARN && it.loggerName.startsWith("me.ahoo.cosec.control.audit") }

    private fun event() = ChangeEvent(
        "e",
        Instant.EPOCH,
        ChangeEvent.Kind.PUBLISHED,
        Actor(TenantId.PLATFORM, PrincipalId("plato")),
        ChangeId("c1"),
        SnapshotKey.Platform,
        RevisionRef(1, "d"),
        null,
    )

    private fun listeners(listener: ChangeListener) = ChangeListeners(listOf(listener)).also { opened += it }

    private class Scripted(
        override val delivery: Delivery,
        private val script: suspend (Int) -> Unit,
    ) : ChangeListener {
        override val name = "scripted"
        val calls = AtomicInteger()

        override suspend fun onChange(event: ChangeEvent) {
            script(calls.incrementAndGet())
        }
    }

    @Test
    fun `a listener's own timeout is logged like another delivery failure`() = runBlocking<Unit> {
        val listener = Scripted(Delivery.BEST_EFFORT) { call ->
            if (call == 1) error("down") else withTimeout(10) { awaitCancellation() }
        }
        val registered = listeners(listener)

        registered.publish(event())
        registered.publish(event())
        registered.awaitDelivered()

        listener.calls.get().assert().isEqualTo(2)
        warnings().assert().hasSize(2)
        warnings().last().formattedMessage.assert().contains("scripted").contains("could not record")
    }

    @Test
    fun `a cancellation a listener throws is logged, for a REQUIRED listener's event that is not gated too`() =
        runBlocking<Unit> {
            val listener = Scripted(Delivery.REQUIRED) { throw CancellationException("the future was cancelled") }
            val registered = listeners(listener)

            registered.publish(event())
            registered.awaitDelivered()

            // The event of a stored step a REQUIRED listener did not record is lost: counted, and logged at ERROR.
            val errors = appender.captured().filter { it.level == Level.ERROR }
            errors.assert().hasSize(1)
            errors.single().formattedMessage.assert().contains("scripted").contains("the future was cancelled")
            registered.queues.single().lost.assert().isEqualTo(1L)
        }

    /** The negative side: the host stopping the delivery is not a failure of the listener, and says nothing. */
    @Test
    fun `the host cancelling the delivery is quiet`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val listener = Scripted(Delivery.BEST_EFFORT) {
            started.complete(Unit)
            awaitCancellation()
        }
        val registered = listeners(listener)

        registered.publish(event())
        started.await()
        registered.cancel()
        registered.awaitDelivered()

        warnings().assert().isEmpty()
    }
}
