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

package me.ahoo.cosec.opentelemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.TraceContext
import kotlin.coroutines.CoroutineContext

/**
 * Captures the OpenTelemetry context where the adapter bridges a request (RFC 0006 §7.5), the one place
 * `Context.current()` is still the request's: once the pipeline suspends, another thread may resume it. Its context
 * element makes it current whenever the pipeline resumes, so instrumented clients in providers make child spans of
 * the request's span; the [TraceContext] puts the parent span into decision events and audit records.
 */
@InternalCoSecApi
object OpenTelemetryContextContributor : BridgeContextContributor {
    override fun capture(): CoroutineContext {
        val context = Context.current()
        val element = context.asContextElement()
        val span = Span.fromContext(context).spanContext
        return if (span.isValid) {
            element + TraceContext(span.traceId, span.spanId, span.isSampled, headerOf(span.traceState))
        } else {
            element
        }
    }

    /**
     * The tracestate in its W3C header form, the SDK's own order. Most requests have none, and for those it is the empty
     * string and nothing is allocated (#1404); with one, it is the one `String` (and the builder that makes it).
     */
    private fun headerOf(state: TraceState): String {
        if (state.isEmpty) {
            return ""
        }
        val header = StringBuilder()
        state.forEach { key, value ->
            if (header.isNotEmpty()) {
                header.append(',')
            }
            header.append(key).append('=').append(value)
        }
        return header.toString()
    }
}
