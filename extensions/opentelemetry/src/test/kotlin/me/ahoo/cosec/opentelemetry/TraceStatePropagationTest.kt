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

package me.ahoo.cosec.opentelemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.TraceContext
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * #1404: the `cosec.authorize` span is a child of the bridged parent with the parent's tracestate, as the SDK's own
 * child spans are. The tracestate travels in the event's [TraceContext] as an opaque W3C header string.
 */
class TraceStatePropagationTest {
    private val spans = CopyOnWriteArrayList<SpanData>()
    private val exporter = object : SpanExporter {
        override fun export(batch: Collection<SpanData>): CompletableResultCode {
            spans += batch
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
    private val tracerProvider = SdkTracerProvider.builder().addSpanProcessor(
        SimpleSpanProcessor.create(exporter)
    ).build()
    private val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()

    @AfterEach
    fun close() {
        tracerProvider.close()
    }

    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    /** Evaluates one request under [context], waits for the `cosec.authorize` span and returns it. */
    private fun authorizeSpanUnder(context: CoroutineContext): SpanData {
        val listeners = DecisionListeners(listOf(TracingListener(openTelemetry)))
        val engine = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel) =
                FlatEvaluation(
                    Decision.Permit(StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Attached(PolicyId("p")))),
                    null
                )
        }
        val pipeline = Pipeline.builder({ AuthnResult.Anonymous }, SuspendingSnapshotSource(Views))
            .engine(engine)
            .listeners(listeners)
            .build()
        runBlocking {
            withContext(Dispatchers.Default + context) {
                pipeline.evaluate(
                    HttpFacts(
                        "GET",
                        "/orders",
                        null,
                        Headers.of(emptyMap()),
                        Cookies.EMPTY,
                        "10.0.0.1",
                        HostScope(null)
                    )
                ).status.assert().isEqualTo(200)
            }
            withTimeout(5.seconds) {
                while (spans.none { it.name == TracingListener.SPAN_NAME }) {
                    delay(5)
                }
            }
        }
        listeners.close()
        return spans.single { it.name == TracingListener.SPAN_NAME }
    }

    /** A sampled remote parent carrying [header] as its tracestate, and the local HTTP span under it. */
    private fun httpSpanUnder(header: String): Span {
        val remote = SpanContext.createFromRemoteParent(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            TraceFlags.getSampled(),
            header.split(',').filter { it.isNotEmpty() }.asReversed().fold(TraceState.builder()) { builder, member ->
                builder.put(member.substringBefore('='), member.substringAfter('='))
            }.build(),
        )
        return openTelemetry.getTracer("test").spanBuilder("http")
            .setParent(Context.root().with(Span.wrap(remote))).startSpan()
    }

    private fun bridged(parent: Span): CoroutineContext =
        parent.storeInContext(Context.root()).makeCurrent().use {
            BridgeContextContributor.capture(listOf(OpenTelemetryContextContributor))
        }

    @Test
    fun theSpanInheritsTheBridgedParentsTracestate() {
        val parent = httpSpanUnder("vendor=opaque-value,other=x")
        // The SDK's own child span is the reference.
        val reference = openTelemetry.getTracer("test").spanBuilder("control")
            .setParent(Context.root().with(parent)).startSpan()
        val context = bridged(parent)

        context[TraceContext]!!.traceState.assert().isEqualTo("vendor=opaque-value,other=x")
        val span = authorizeSpanUnder(context)

        span.parentSpanId.assert().isEqualTo(parent.spanContext.spanId)
        span.spanContext.traceState.get("vendor").assert().isEqualTo("opaque-value")
        span.spanContext.traceState.asMap().assert().isEqualTo(reference.spanContext.traceState.asMap())
        // The order is the SDK's: the header a downstream hop would read is the one this hop was given.
        span.spanContext.traceState.asMap().keys.toList().assert().isEqualTo(listOf("vendor", "other"))
    }

    @Test
    fun aParentWithoutATracestateAddsNothingToTheContext() {
        val context = bridged(httpSpanUnder(""))

        context[TraceContext]!!.traceState.assert().isEmpty()
        authorizeSpanUnder(context).spanContext.traceState.isEmpty.assert().isTrue()
    }

    /** A value the SDK refuses makes no tracestate: the span is still made, under the right parent. */
    @Test
    fun aValueTheSdkRefusesIsNoTracestateAndNeverALostSpan() {
        val oversized = (1..30).joinToString(",") { "vendor$it=" + "v".repeat(20) }
        val tooMany = (1..40).joinToString(",") { "k$it=v" }
        listOf(oversized, tooMany, "no-equals-sign", "UPPER=case", "a=b,,c", "a=b\nc=d").forEach { bad ->
            spans.clear()
            val parent = httpSpanUnder("")
            val context = parent.storeInContext(Context.root()).makeCurrent().use {
                BridgeContextContributor.capture(listOf(OpenTelemetryContextContributor))
            } + TraceContext(parent.spanContext.traceId, parent.spanContext.spanId, true, bad)

            val span = authorizeSpanUnder(context)

            span.parentSpanId.assert().describedAs(bad).isEqualTo(parent.spanContext.spanId)
            span.traceId.assert().isEqualTo(parent.spanContext.traceId)
            span.spanContext.traceState.isEmpty.assert().describedAs(bad).isTrue()
        }
    }
}
