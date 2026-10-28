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

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RFC 0006 X8: on shutdown, queued BEST_EFFORT events still go out, for up to the drain timeout. */
class ListenerDrainTest {
    private class Counting(private val each: Duration) : DecisionListener {
        override val name = "counting"
        val delivered = AtomicInteger()

        override suspend fun onDecision(event: DecisionEvent) {
            delay(each)
            delivered.incrementAndGet()
        }
    }

    private fun publish(listeners: DecisionListeners, count: Int) = runBlocking {
        val pipeline = PipelineFixtures.pipeline { it.copy(listeners = listeners) }
        repeat(count) { pipeline.evaluate(PipelineFixtures.facts()) }
    }

    @Test
    fun `draining delivers what is queued`() {
        val listener = Counting(5.milliseconds)
        val listeners = DecisionListeners(listOf(listener), drainTimeout = 5.seconds)
        publish(listeners, 20)
        listeners.drain().assert().isTrue()
        listener.delivered.get().assert().isEqualTo(20)
    }

    @Test
    fun `draining gives up at its timeout and drops the rest`() {
        val listener = Counting(10.seconds)
        val listeners = DecisionListeners(listOf(listener), drainTimeout = 100.milliseconds)
        publish(listeners, 3)
        val started = System.nanoTime()
        listeners.drain().assert().isFalse()
        ((System.nanoTime() - started) / 1_000_000).assert().isLessThan(5_000)
        listener.delivered.get().assert().isEqualTo(0)
    }

    @Test
    fun `an interrupted drain stops waiting and keeps the interrupt`() {
        val listener = Counting(10.seconds)
        val listeners = DecisionListeners(listOf(listener), drainTimeout = 5.seconds)
        publish(listeners, 1)
        Thread.currentThread().interrupt()
        try {
            listeners.drain().assert().isFalse()
            Thread.currentThread().isInterrupted.assert().isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancelling drops what is queued`() {
        val listener = Counting(10.seconds)
        val listeners = DecisionListeners(listOf(listener))
        publish(listeners, 2)
        listeners.cancel()
        listener.delivered.get().assert().isEqualTo(0)
    }
}
