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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** Review A2: `cosec.pipeline.trace-level` reaches the decision events, and `OFF` carries nothing. */
class TraceLevelEventTest {
    private class Capture : DecisionListener {
        override val name = "capture"
        override val delivery = Delivery.REQUIRED
        var event: DecisionEvent? = null

        override suspend fun onDecision(event: DecisionEvent) {
            this.event = event
        }
    }

    private fun event(level: TraceLevel): DecisionEvent {
        val capture = Capture()
        val listeners = DecisionListeners(listOf(capture))
        try {
            runBlocking {
                PipelineFixtures.pipeline(engine = CoSecFlatEngine) {
                    it.copy(listeners = listeners, options = PipelineOptions(traceLevel = level))
                }.evaluate(PipelineFixtures.facts())
            }
        } finally {
            listeners.cancel()
        }
        return capture.event!!
    }

    @Test
    fun theTraceReachesTheEventAtTheConfiguredLevel() {
        event(TraceLevel.FULL).evaluationTrace.assert().isNotNull()
        event(TraceLevel.EXHAUSTIVE).evaluationTrace.assert().isNotNull()
        event(TraceLevel.OFF).evaluationTrace.assert().isNull()
    }
}
