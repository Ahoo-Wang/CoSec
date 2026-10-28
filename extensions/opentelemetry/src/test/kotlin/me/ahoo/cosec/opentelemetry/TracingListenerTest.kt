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

import io.opentelemetry.api.trace.Span
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
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.TraceContext
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** RFC 0006 §9.5.1: the `cosec.authorize` span is made afterwards, under the bridged parent, with explicit timestamps. */
class TracingListenerTest {
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

    @Test
    fun theSpanIsAChildOfTheBridgedParentWithTheEventsTimes() {
        val listeners = DecisionListeners(listOf(TracingListener(openTelemetry)))
        val bound = StatementRef.Policy(
            PolicyId("orders"),
            2,
            "read",
            BindingRef.Bound(BindingId("g:orders"), DocumentKind.PLATFORM)
        )
        val engine = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel) =
                FlatEvaluation(Decision.Permit(bound), null)
        }
        val pipeline = Pipeline(
            PipelineComponents(
                { AuthnResult.Anonymous },
                SuspendingSnapshotSource(Views),
                engine = engine,
                listeners = listeners
            )
        )
        val parent = openTelemetry.getTracer("test").spanBuilder("http").startSpan()
        val bridged = parent.storeInContext(Context.root()).makeCurrent().use {
            BridgeContextContributor.capture(listOf(OpenTelemetryContextContributor))
        }
        runBlocking {
            withContext(Dispatchers.Default + bridged) {
                // The OTel context element makes the bridged parent current after every resumption.
                Span.current().spanContext.spanId.assert().isEqualTo(parent.spanContext.spanId)
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
                )
            }.status.assert().isEqualTo(200)
            withTimeout(5.seconds) {
                while (spans.none { it.name == TracingListener.SPAN_NAME }) {
                    delay(5)
                }
            }
        }
        listeners.close()
        val span = spans.single { it.name == TracingListener.SPAN_NAME }
        span.traceId.assert().isEqualTo(parent.spanContext.traceId)
        span.parentSpanId.assert().isEqualTo(parent.spanContext.spanId)
        span.attributes.get(TracingListener.POLICY_ID).assert().isEqualTo("orders")
        span.attributes.get(TracingListener.STATEMENT_INDEX).assert().isEqualTo(2L)
        span.attributes.get(TracingListener.BINDING_ID).assert().isEqualTo("g:orders")
        span.attributes.get(TracingListener.RESULT).assert().isEqualTo("PERMIT")
        span.endEpochNanos.assert().isGreaterThanOrEqualTo(span.startEpochNanos)
    }

    @Test
    fun withoutABridgedSpanTheContributorAddsNoTraceAndTheSpanIsARoot() {
        BridgeContextContributor.capture(listOf(OpenTelemetryContextContributor))[TraceContext].assert().isNull()
    }
}
