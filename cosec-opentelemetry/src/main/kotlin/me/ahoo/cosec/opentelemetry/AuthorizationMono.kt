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
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.context.Context
import me.ahoo.cosec.api.audit.AuditTrace
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono

class CoSecMonoTrace<T : Any>(
    private val parentContext: Context,
    private val securityContext: SecurityContext,
    private val request: Request,
    private val source: (Request) -> Mono<T>,
) : Mono<T>() {
    override fun subscribe(actual: CoreSubscriber<in T>) {
        if (!CoSecInstrumenter.INSTRUMENTER.shouldStart(parentContext, securityContext)) {
            Mono.defer { source(request) }.subscribe(actual)
            return
        }
        val otelContext = CoSecInstrumenter.INSTRUMENTER.start(parentContext, securityContext)
        val spanContext = Span.fromContext(otelContext).spanContext
        val tracedRequest = request.withAuditTrace(spanContext)
        otelContext.makeCurrent().use {
            Mono.defer { source(tracedRequest) }
                .subscribe(TraceFilterSubscriber(otelContext, securityContext, actual))
        }
    }
}

internal fun Request.withAuditTrace(spanContext: SpanContext): Request {
    if (!spanContext.isValid) {
        return this
    }
    return mergeAttributes(
        mapOf(
            AuditTrace.TRACE_ID_ATTRIBUTE_KEY to spanContext.traceId,
            AuditTrace.SPAN_ID_ATTRIBUTE_KEY to spanContext.spanId,
        ),
    )
}

class TraceFilterSubscriber<T : Any>(
    private val otelContext: Context,
    private val securityContext: SecurityContext,
    private val actual: CoreSubscriber<in T>
) : CoreSubscriber<T> {
    override fun currentContext(): reactor.util.context.Context {
        return actual.currentContext()
    }

    override fun onSubscribe(subscription: Subscription) {
        actual.onSubscribe(subscription)
    }

    override fun onNext(value: T) {
        actual.onNext(value)
    }

    override fun onError(throwable: Throwable) {
        CoSecInstrumenter.INSTRUMENTER.end(otelContext, securityContext, null, throwable)
        actual.onError(throwable)
    }

    override fun onComplete() {
        CoSecInstrumenter.INSTRUMENTER.end(otelContext, securityContext, null, null)
        actual.onComplete()
    }
}
