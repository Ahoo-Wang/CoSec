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

package me.ahoo.cosec.webflux.testing

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.writeSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

/**
 * For tests only: how a test of a WebFlux application puts CoSec in front of the code under test (`PIP-29`). In
 * production the adapter binds every context, and nothing else does (round 2 L5): an application never imports this.
 *
 * - [filter] is CoSec's own filter over a test pipeline (`PipelineFixture.pipeline`, or [me.ahoo.cosec.runtime.Pipeline.behindGateway]
 *   for a service behind a gateway): the faithful form for a handler test with `WebTestClient`, or a `@WebFluxTest`
 *   slice that declares it as a bean. A slice needs no CoSec auto-configuration: it needs the filter.
 * - [withSecurityContext] and [bind] put a context the kit made (`PermittedRequest`, `PipelineFixture.securityContext`)
 *   where the code under test reads it: the Reactor context, the exchange. For a unit test of a function.
 *
 * Every binder refuses a context the pipeline did not make, so a test never runs as anonymous without saying so; a bound
 * context whose request has ended is denied as any other (`PIP-26`).
 */
@ExperimentalCoSecApi
object CoSecTestBindings {
    /** CoSec's filter over [gate], built as the starter builds it: the same class, order, problem writer and request end. */
    @JvmStatic
    fun filter(gate: RequestGate): WebFilter = PipelineWebFilter(gate)

    /** [mono] with [context] in its Reactor context, as the adapter writes it for the chain. */
    @JvmStatic
    fun <T : Any> withSecurityContext(mono: Mono<T>, context: SecurityContext): Mono<T> =
        mono.writeSecurityContext(pipelineMade(context))

    /** [exchange] with [context] bound, as the adapter binds it for the handler. */
    @JvmStatic
    fun bind(exchange: ServerWebExchange, context: SecurityContext) {
        exchange.setSecurityContext(pipelineMade(context))
    }

    private fun pipelineMade(context: SecurityContext): SecurityContext {
        require(RequestSecurity.of(context) != null) { REFUSED }
        return context
    }

    internal const val REFUSED = "Only a context CoSec's pipeline made can be bound: use PermittedRequest.withToken, " +
        "forSubject or withIdentity, or PipelineFixture.securityContext (cosec-testkit). A context made by hand reads " +
        "as no context at all (PIP-27)."
}
