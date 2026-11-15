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

package me.ahoo.cosec.runtime.audit

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.audit.AuditEventSink

/**
 * A [DecisionListener] over an [AuditEventSink] (RFC 0006 §9.5.3): maps each event with [AuditEvents.from], so a sink
 * receives the stable audit record and needs no knowledge of the engine's types.
 *
 * Always BEST_EFFORT: `AuditEventSink.publish` returns before anything is written and reports no failure, so it can
 * never acknowledge a write. As a REQUIRED listener it would always "succeed", and a break-glass permit would proceed
 * with no audit record (§9.4.1, §9.4.3). A REQUIRED audit awaits its store, as `KafkaAuditListener` does, or is a
 * [DecisionListener] of its own.
 */
class AuditListener private constructor(
    override val name: String,
    private val sink: AuditEventSink,
) : DecisionListener {
    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    override suspend fun onDecision(event: DecisionEvent) {
        sink.publish(AuditEvents.from(event))
    }

    override fun toString(): String = "AuditListener($name)"

    companion object {
        fun of(sink: AuditEventSink, name: String): AuditListener = AuditListener(name, sink)
    }
}
