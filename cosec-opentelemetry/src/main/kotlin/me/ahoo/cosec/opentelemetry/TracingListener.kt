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

package me.ahoo.cosec.opentelemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.TraceContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Makes the `cosec.authorize` span of each decision event afterwards, from the event's start time and duration, under
 * the span the adapter's bridge captured (RFC 0006 §9.5.1, K6, Q21). It observes only: no hook wraps the evaluation,
 * so tracing can neither fail nor hold up a decision. The I/O of the evaluation is a sibling of this span, under the same
 * parent (X3).
 *
 * The attribute keys are those of 7.x's tracing decorator, plus `cosec.snapshot.version` and `cosec.binding.id`.
 */
class TracingListener(openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get()) : DecisionListener {
    private val tracer: Tracer = openTelemetry.getTracer(
        INSTRUMENTATION_NAME,
        INSTRUMENTATION_VERSION
    )

    override val name: String get() = NAME

    override val delivery: Delivery get() = Delivery.BEST_EFFORT

    override suspend fun onDecision(event: DecisionEvent) {
        val startNanos = TimeUnit.MILLISECONDS.toNanos(event.time)
        val span = tracer.spanBuilder(SPAN_NAME)
            .setParent(parent(event.trace))
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
            .startSpan()
        span.setAttribute(TENANT_ID, event.subject.tenant)
        span.setAttribute(USER_ID, event.subject.id)
        span.setAttribute(REQUEST_ID, event.requestId)
        span.setAttribute(RESULT, event.outcome.reason.name)
        event.scope?.let { scope ->
            scope.app.value.takeIf { it.isNotEmpty() }?.let { span.setAttribute(APP_ID, it) }
            scope.space.value.takeIf { it.isNotEmpty() }?.let { span.setAttribute(SPACE_ID, it) }
        }
        event.request.deviceId.takeIf { it.isNotEmpty() }?.let { span.setAttribute(DEVICE_ID, it) }
        (event.by as? StatementRef.Policy)?.let { by ->
            span.setAttribute(POLICY_ID, by.policy.value)
            span.setAttribute(STATEMENT_INDEX, by.index.toLong())
            span.setAttribute(STATEMENT_NAME, by.name)
            (by.via as? BindingRef.Bound)?.let { span.setAttribute(BINDING_ID, it.id.value) }
        }
        event.snapshotVersion?.let {
            span.setAttribute(
                SNAPSHOT_VERSION,
                "${it.platform}/${it.app ?: "-"}/${it.tenant ?: "-"}"
            )
        }
        if (event.kind == DecisionEvent.Kind.ERROR) {
            span.setStatus(StatusCode.ERROR)
        }
        span.end(startNanos + event.elapsedNanos, TimeUnit.NANOSECONDS)
    }

    private fun parent(trace: TraceContext?): Context {
        trace ?: return Context.root()
        val flags = if (trace.sampled) TraceFlags.getSampled() else TraceFlags.getDefault()
        val parent = SpanContext.create(trace.traceId, trace.spanId, flags, TraceState.getDefault())
        return if (parent.isValid) Context.root().with(Span.wrap(parent)) else Context.root()
    }

    companion object {
        const val NAME = "tracing"
        const val SPAN_NAME = "cosec.authorize"
        private const val INSTRUMENTATION_NAME = "me.ahoo.cosec"
        private val INSTRUMENTATION_VERSION = TracingListener::class.java.`package`.implementationVersion ?: "2.15.0"
        private const val AUTHORIZE = CoSec.COSEC_PREFIX + "authorize."
        val TENANT_ID = stringKey(CoSec.COSEC_PREFIX + "tenant_id")
        val APP_ID = stringKey(CoSec.COSEC_PREFIX + "app_id")
        val SPACE_ID = stringKey(CoSec.COSEC_PREFIX + "space_id")
        val REQUEST_ID = stringKey(CoSec.COSEC_PREFIX + "request_id")
        val DEVICE_ID = stringKey("device.id")
        val USER_ID = stringKey("user.id")
        val RESULT = stringKey(AUTHORIZE + "result")
        val POLICY_ID = stringKey(AUTHORIZE + "policy.id")
        val STATEMENT_INDEX = longKey(AUTHORIZE + "statement.index")
        val STATEMENT_NAME = stringKey(AUTHORIZE + "statement.name")
        val BINDING_ID = stringKey(CoSec.COSEC_PREFIX + "binding.id")
        val SNAPSHOT_VERSION = stringKey(CoSec.COSEC_PREFIX + "snapshot.version")
    }
}

/**
 * Captures the OpenTelemetry context where the adapter bridges a request (RFC 0006 §7.5): at the same point the 7.x
 * `TracingAuthorization` read `Context.current()`. Its context element makes it current whenever the pipeline resumes,
 * so instrumented clients in providers make child spans as before; the [TraceContext] puts the parent span into decision
 * events and audit records.
 */
object OpenTelemetryContextContributor : BridgeContextContributor {
    override fun capture(): CoroutineContext {
        val context = Context.current()
        val element = context.asContextElement()
        val span = Span.fromContext(context).spanContext
        return if (span.isValid) element + TraceContext(span.traceId, span.spanId, span.isSampled) else element
    }
}
