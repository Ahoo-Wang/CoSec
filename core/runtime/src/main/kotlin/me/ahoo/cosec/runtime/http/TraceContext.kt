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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The trace the request belongs to, as the adapter's bridge captured it (RFC 0006 §7.5): put into the coroutine
 * context by a [BridgeContextContributor], read by the pipeline for events and problem bodies.
 *
 * [traceState] is the parent span's `tracestate` in its W3C header form (`vendor=value,other=value`), kept opaque so
 * that a span made afterwards can be a child of the right parent, tracestate and all (#1404); empty for none. It is
 * vendor data: CoSec writes it onto that span's parent and nowhere else (not into audit records, Kafka records, logs or
 * metric tags), and a [me.ahoo.cosec.runtime.event.DecisionListener] that receives it with the event must not log it.
 */
class TraceContext(
    val traceId: String,
    val spanId: String,
    val sampled: Boolean,
    val traceState: String,
) : AbstractCoroutineContextElement(Key) {
    /** A trace without a tracestate. */
    constructor(traceId: String, spanId: String, sampled: Boolean) : this(traceId, spanId, sampled, "")

    companion object Key : CoroutineContext.Key<TraceContext>

    override fun toString(): String = "TraceContext($traceId/$spanId)"
}
