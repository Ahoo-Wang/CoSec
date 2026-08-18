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
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono

class CoSecMonoTrace(
    private val parentContext: Context,
    private val securityContext: SecurityContext,
    private val request: Request,
    private val delegate: Authorization,
) : Mono<AuthorizeResult>() {
    override fun subscribe(actual: CoreSubscriber<in AuthorizeResult>) {
        if (!CoSecInstrumenter.INSTRUMENTER.shouldStart(parentContext, securityContext)) {
            Mono.defer { delegate.authorize(request, securityContext) }.subscribe(actual)
            return
        }
        val otelContext = CoSecInstrumenter.INSTRUMENTER.start(parentContext, securityContext)
        val spanContext = Span.fromContext(otelContext).spanContext
        val tracedRequest = request.withAuditTrace(spanContext)
        otelContext.makeCurrent().use {
            Mono.defer { delegate.authorize(tracedRequest, securityContext) }
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

class TraceFilterSubscriber(
    private val otelContext: Context,
    private val securityContext: SecurityContext,
    private val actual: CoreSubscriber<in AuthorizeResult>
) : CoreSubscriber<AuthorizeResult> {
    override fun currentContext(): reactor.util.context.Context {
        return actual.currentContext()
    }

    override fun onSubscribe(subscription: Subscription) {
        actual.onSubscribe(subscription)
    }

    override fun onNext(authorizeResult: AuthorizeResult) {
        actual.onNext(authorizeResult)
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
