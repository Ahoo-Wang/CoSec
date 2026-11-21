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
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowListener
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * `OBS-8`: a shadow listener is offered only the events it sampled, and when the shadow listeners are the only
 * BEST_EFFORT listeners and none samples a request, no event is made for it. A REQUIRED listener gets every event, and
 * another BEST_EFFORT listener every event, whatever the shadow listeners sample.
 */
class SampledOnlyEventsTest {
    private val opened = ArrayList<DecisionListeners>()
    private val recorder = ShadowRecorder("instance-1")

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

    /** A candidate source that counts the requests a shadow listener evaluated, and stages nothing. */
    private class Asked : CandidateSource {
        val count = AtomicInteger()

        override suspend fun candidate(key: SnapshotKey): Candidate? {
            count.incrementAndGet()
            return null
        }
    }

    /** Evaluates [requests] requests with [listeners]; answers how many events were offered to a BEST_EFFORT queue. */
    private fun evaluate(requests: Int, vararg listeners: DecisionListener): Int {
        val registered = DecisionListeners(listeners.toList()).also { opened += it }
        val offers = AtomicInteger()
        registered.beforeOffer = { offers.incrementAndGet() }
        val pipeline = PipelineFixtures.pipeline { listeners(registered) }
        repeat(requests) {
            runBlocking { pipeline.evaluate(PipelineFixtures.facts()) }.status.assert().isEqualTo(200)
        }
        return offers.get()
    }

    private fun eventually(count: Int, read: () -> Int) = runBlocking {
        withTimeout(5.seconds) {
            while (read() < count) {
                delay(5)
            }
        }
    }

    @Test
    fun `with only shadow listeners, a request none samples makes no event and a REQUIRED listener still gets it`() {
        val audit = Recording("audit", Delivery.REQUIRED)
        val unsampled = ShadowListener("shadow", 0.0, { error("never asked") }, recorder)
        evaluate(3, audit, unsampled).assert().isZero()
        audit.events.size.assert().isEqualTo(3)
        audit.events.forEach { it.replay.assert().isNull() }
    }

    /** The ids a request asks for: one per event made, beside whatever else the pipeline names. */
    private fun ids(vararg listeners: DecisionListener): Int {
        val registered = DecisionListeners(listeners.toList()).also { opened += it }
        val asked = AtomicInteger()
        val pipeline = PipelineFixtures.pipeline {
            listeners(registered)
            ids(IdGenerator { "id-${asked.incrementAndGet()}" })
        }
        runBlocking { pipeline.evaluate(PipelineFixtures.facts()) }.status.assert().isEqualTo(200)
        return asked.get()
    }

    @Test
    fun `no event is made when only shadow listeners listen and none samples`() {
        val none = ids()
        ids(ShadowListener("shadow", 0.0, { error("never asked") }, recorder)).assert().isEqualTo(none)
        // A listener that is offered events costs the event's id.
        ids(Recording("metrics", Delivery.BEST_EFFORT)).assert().isEqualTo(none + 1)
    }

    @Test
    fun `a request a shadow listener samples is offered to it`() {
        val asked = Asked()
        evaluate(2, ShadowListener("shadow", 1.0, asked, recorder)).assert().isEqualTo(2)
        // The platform's document, once per request.
        eventually(2) { asked.count.get() }
    }

    @Test
    fun `another BEST_EFFORT listener gets every event, and an unsampled shadow listener none`() {
        val metrics = Recording("metrics", Delivery.BEST_EFFORT)
        val unsampled = ShadowListener("shadow", 0.0, { error("never asked") }, recorder)
        evaluate(3, metrics, unsampled).assert().isEqualTo(3)
        eventually(3) { metrics.events.size }
    }

    @Test
    fun `each shadow listener is offered only the events it sampled`() {
        val asked = Asked()
        val sampled = ShadowListener("sampled", 1.0, asked, recorder)
        val unsampled = ShadowListener("unsampled", 0.0, { error("never asked") }, recorder)
        evaluate(2, sampled, unsampled).assert().isEqualTo(2)
        eventually(2) { asked.count.get() }
    }
}
