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

import io.opentelemetry.context.Context
import org.reactivestreams.Subscription
import org.springframework.web.server.ServerWebExchange
import reactor.core.CoreSubscriber
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Mono

class CoSecMonoTrace(
    private val exchange: ServerWebExchange,
    private val chain: Mono<Void>,
) : Mono<Void>() {
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        val parentContext = Context.current()
        if (!CoSecInstrumenter.INSTRUMENTER.shouldStart(parentContext, exchange)) {
            chain.subscribe(actual)
            return
        }
        val context = CoSecInstrumenter.INSTRUMENTER.start(parentContext, exchange)
        context.makeCurrent().use {
            chain.subscribe(TraceFilterSubscriber(context, exchange, actual))
        }
    }
}

class TraceFilterSubscriber(
    private val otelContext: Context,
    private val exchange: ServerWebExchange,
    private val actual: CoreSubscriber<in Void>
) : BaseSubscriber<Void>() {
    override fun currentContext(): reactor.util.context.Context {
        return actual.currentContext()
    }

    override fun hookOnSubscribe(subscription: Subscription) {
        actual.onSubscribe(this)
    }

    override fun hookOnError(throwable: Throwable) {
        try {
            otelContext.makeCurrent().use {
                actual.onError(throwable)
            }
        } finally {
            CoSecInstrumenter.INSTRUMENTER.end(otelContext, exchange, null, throwable)
        }
    }

    override fun hookOnCancel() {
        CoSecInstrumenter.INSTRUMENTER.end(otelContext, exchange, null, null)
    }

    override fun hookOnComplete() {
        try {
            otelContext.makeCurrent().use {
                actual.onComplete()
            }
        } finally {
            CoSecInstrumenter.INSTRUMENTER.end(otelContext, exchange, null, null)
        }
    }
}
