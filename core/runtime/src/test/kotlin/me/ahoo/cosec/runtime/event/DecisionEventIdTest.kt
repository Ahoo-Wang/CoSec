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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * DR1-C-6: one request's decision events carry one id, so a REQUIRED audit store and a BEST_EFFORT metrics pipeline can
 * join them; and making the BEST_EFFORT event, after the outcome exists, never fails the request: what throws is a drop,
 * counted.
 */
class DecisionEventIdTest {
    private val opened = ArrayList<DecisionListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    private class Recording(override val name: String, override val delivery: Delivery) : DecisionListener {
        val events = CopyOnWriteArrayList<DecisionEvent>()

        override suspend fun onDecision(event: DecisionEvent) {
            events += event
        }
    }

    private fun registered(vararg listeners: DecisionListener) =
        DecisionListeners(listeners.toList()).also { opened += it }

    @Test
    fun `a request's REQUIRED and BEST_EFFORT events carry one id`() = runBlocking<Unit> {
        val audit = Recording("audit", Delivery.REQUIRED)
        val metrics = Recording("metrics", Delivery.BEST_EFFORT)
        val pipeline = PipelineFixtures.pipeline { listeners(registered(audit, metrics)) }
        repeat(2) { pipeline.evaluate(PipelineFixtures.facts()).status.assert().isEqualTo(200) }
        withTimeout(5.seconds) {
            while (metrics.events.size < 2) delay(5)
        }
        audit.events.map { it.id }.assert().isEqualTo(metrics.events.map { it.id })
        audit.events.map { it.id }.toSet().assert().hasSize(2)
    }

    @Test
    fun `a BEST_EFFORT event that cannot be made is a counted drop, never an error of the request`() = runBlocking<Unit> {
        val failing = object : SampledOnlyListener {
            override val name = "shadow"
            override val delivery = Delivery.BEST_EFFORT

            override fun sample(scope: Scope, view: SnapshotView): Boolean = error("the sampler failed")

            override suspend fun onDecision(event: DecisionEvent) = Unit
        }
        val listeners = registered(failing)
        val pipeline = PipelineFixtures.pipeline { listeners(listeners) }
        pipeline.evaluate(PipelineFixtures.facts()).status.assert().isEqualTo(200)
        listeners.stats.single().dropped.assert().isEqualTo(1)
    }
}
