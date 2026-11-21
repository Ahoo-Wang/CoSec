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
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * R2D-2: while a REQUIRED listener fails, every request counts its failure, and the ERROR line about it is written at
 * most once per warn interval, never on the request's thread (as #1392 has it for the queues).
 */
class RequiredFailureLogTest {
    private class Failing : DecisionListener {
        override val name = "audit"
        override val delivery = Delivery.REQUIRED

        override suspend fun onDecision(event: DecisionEvent): Unit = throw IllegalStateException(
            "the audit store is down"
        )
    }

    @Test
    fun `a failing REQUIRED listener is counted on every request and logged once per interval, off the request's thread`() {
        val lines = CopyOnWriteArrayList<Pair<String, String>>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if ("REQUIRED" in event.formattedMessage) lines += event.formattedMessage to event.threadName
            }
        }.also { it.start() }
        val logger = LoggerFactory.getLogger(DecisionListeners::class.java) as Logger
        logger.addAppender(appender)
        val listeners = DecisionListeners(listOf(Failing()))
        try {
            // A permit turned into 503 by the failure, and a refusal that keeps its status: both kinds of line.
            val permitted = PipelineFixtures.pipeline(authenticator = authenticator(authenticated())) {
                listeners(listeners)
            }
            val refused = PipelineFixtures.pipeline(engine = PipelineFixtures.StubEngine(Decision.Deny(null))) {
                listeners(listeners)
            }
            val threads = mutableSetOf<String>()
            repeat(REQUESTS) {
                runBlocking {
                    threads += Thread.currentThread().name
                    permitted.evaluate(PipelineFixtures.facts("/orders/1"))
                    refused.evaluate(PipelineFixtures.facts("/orders/1"))
                }
            }
            listeners.stats.single().failures.assert().isEqualTo(2L * REQUESTS)
            listeners.requiredFailuresOnNonPermits.assert().isGreaterThan(0L)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
            while (lines.size < 2 && System.nanoTime() < deadline) Thread.onSpinWait()
            // One line for the listener's failures, one for the non-permits: not one or two per request.
            lines.size.assert().isEqualTo(2)
            lines.none { (_, thread) -> thread in threads }.assert().isTrue()
            lines.first { "listener [audit]" in it.first }.first.assert().contains("failures so far")
        } finally {
            logger.detachAppender(appender)
            listeners.cancel()
        }
    }

    private companion object {
        const val REQUESTS = 50
        const val BOUND_SECONDS = 5L
    }
}
