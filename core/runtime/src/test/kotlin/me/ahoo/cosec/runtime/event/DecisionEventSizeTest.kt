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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.GraphLayout

/**
 * `OBS-11`: what one queued decision event retains, measured with JOL over [MEASURED] events of distinct requests (an
 * authenticated principal with a role in two spaces, a gateway's usual headers), less what the pipeline and its
 * documents hold, which every event shares (the decision and the view's version). The listener queues turn their byte share into a count with
 * [HeapBudget.DECISION_EVENT_BYTES] once, at startup, so nothing is measured on the request path: this test holds the
 * constant to the measurement, never below it and at most twice it.
 */
class DecisionEventSizeTest {
    private class Keep : DecisionListener {
        override val name = "keep"
        override val delivery = Delivery.REQUIRED
        val events = ArrayList<DecisionEvent>()

        override suspend fun onDecision(event: DecisionEvent) {
            synchronized(events) { events += event }
        }
    }

    @Test
    fun `the constant a queue counts with is what a decision event retains, within a factor of two`() {
        val keep = Keep()
        val listeners = DecisionListeners(listOf(keep))
        val pipeline = PipelineFixtures.pipeline(
            authenticator = PipelineFixtures.authenticator(
                PipelineFixtures.authenticated(PipelineFixtures.claims("orders" to "viewer", "billing" to "admin"))
            ),
        ) { listeners(listeners) }
        try {
            runBlocking {
                repeat(MEASURED) {
                    pipeline.evaluate(
                        PipelineFixtures.facts(
                            path = "/orders/$it/items",
                            query = "page=$it&size=20",
                            headers = mapOf(
                                "User-Agent" to listOf("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0"),
                                "X-Forwarded-For" to listOf("203.0.113.${it % 250}, 10.0.0.1"),
                                "CoSec-Device-Id" to listOf("device-$it"),
                                "CoSec-Request-Id" to listOf("request-$it"),
                                "Accept" to listOf("application/json"),
                            ),
                        )
                    )
                }
            }
        } finally {
            listeners.cancel()
        }
        keep.events.size.assert().isEqualTo(MEASURED)
        // What every event shares (the fixture's decision, its view's version) is counted once, not per event.
        val events = GraphLayout.parseInstance(*keep.events.toTypedArray())
        val shared = GraphLayout.parseInstance(
            *listOfNotNull(keep.events.first().decision, keep.events.first().snapshotVersion).toTypedArray()
        )
        val perEvent = events.subtract(shared).totalSize() / MEASURED
        println(
            "DecisionEvent: a queued event retains $perEvent B; the constant is ${HeapBudget.DECISION_EVENT_BYTES} B"
        )
        HeapBudget.DECISION_EVENT_BYTES.assert().isGreaterThanOrEqualTo(perEvent).isLessThanOrEqualTo(perEvent * 2)
    }

    private companion object {
        const val MEASURED = 1_000
    }
}
