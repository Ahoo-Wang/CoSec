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

package me.ahoo.cosec.conformance

import kotlinx.coroutines.awaitCancellation
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.SampledOnlyListener
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.bool
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A pipeline scenario's decision listeners (`"listeners"`, RFC 0006 §9): each answers, fails or never answers, REQUIRED
 * ones within `options.requiredTimeoutMillis` (default 1000); one with `"samples"` reads only the events it sampled, as
 * the shadow listener does (`OBS-8`). Each counts the events it received, for `expect.events`.
 */
internal class ScenarioListeners(node: JsonNode?, options: JsonNode) {
    private val received = LinkedHashMap<String, AtomicInteger>()

    val listeners: DecisionListeners

    init {
        val built = node?.values().orEmpty().map { listener ->
            listener.only("name", "delivery", "fails", "hangs", "samples")
            val name = listener.required("name").text()
            val count = AtomicInteger().also { received[name] = it }
            listener.get("samples")?.let { samples -> sampledOnly(name, samples.bool(), count) } ?: answering(listener, name, count)
        }
        val timeout = options.get("requiredTimeoutMillis")?.asLong()?.milliseconds ?: DEFAULT_REQUIRED_TIMEOUT
        listeners = DecisionListeners(built, requiredTimeout = timeout)
    }

    /** The events each listener received, once every event published is delivered. */
    fun events(): Map<String, Int> {
        listeners.drain()
        return received.mapValues { it.value.get() }
    }

    private fun answering(listener: JsonNode, name: String, count: AtomicInteger): DecisionListener {
        val delivery = when (val delivery = listener.get("delivery")?.text() ?: "best-effort") {
            "required" -> Delivery.REQUIRED
            "best-effort" -> Delivery.BEST_EFFORT
            else -> throw ScenarioFormatException("A delivery is required or best-effort, not $delivery")
        }
        val fails = listener.get("fails")?.text()
        val hangs = listener.get("hangs")?.bool() == true
        return object : DecisionListener {
            override val name = name
            override val delivery = delivery

            override suspend fun onDecision(event: DecisionEvent) {
                count.incrementAndGet()
                fails?.let { throw IllegalStateException(it) }
                if (hangs) awaitCancellation()
            }
        }
    }

    /** A BEST_EFFORT listener that reads only the events it sampled (`OBS-8`): every decided request, or none. */
    private fun sampledOnly(name: String, samples: Boolean, count: AtomicInteger) = object : SampledOnlyListener {
        override val name = name

        override fun sample(scope: Scope, view: SnapshotView): Boolean = samples

        override suspend fun onDecision(event: DecisionEvent) {
            count.incrementAndGet()
        }
    }

    private companion object {
        val DEFAULT_REQUIRED_TIMEOUT = 1.seconds
    }
}
