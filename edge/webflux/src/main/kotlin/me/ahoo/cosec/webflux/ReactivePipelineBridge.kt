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

package me.ahoo.cosec.webflux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.call.ResumeDispatcher
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.writeSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * The bridge of the reactive hosts to the coroutine pipeline (RFC 0006 §7.3), shared by [PipelineWebFilter] and the
 * gateway's filter. It translates and never decides: it writes the [Outcome]'s status, headers and problem body, and
 * calls the chain only when the outcome proceeds.
 *
 * The request starts on the event loop without a dispatch (`Dispatchers.Unconfined`) and, after a real suspension,
 * goes on on `Dispatchers.Default` ([ResumeDispatcher]), never on a client library's I/O thread. A client that goes
 * away cancels the subscription, and so the coroutine: no outcome exists, and nothing is written.
 */
@InternalCoSecApi
abstract class ReactivePipelineBridge(
    val pipeline: RequestGate,
    private val contributors: List<BridgeContextContributor> = emptyList(),
    private val remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
    /** The app this host serves (RFC 0004 §4.6); [HttpFactsReader.NO_HOST] when the request declares it. */
    private val host: HostScope = HttpFactsReader.NO_HOST,
) {
    /** The host and request attributes of [exchange]; the gateway adds its route's. */
    protected open fun read(exchange: ServerWebExchange): HttpFacts =
        HttpFactsReader.read(exchange, remoteIpResolver, host)

    fun filterInternal(
        exchange: ServerWebExchange,
        chain: (ServerWebExchange, Outcome) -> Mono<Void>,
    ): Mono<Void> {
        // Captured on the subscribing thread, where the host's trace and MDC are current (§7.5).
        val context = Dispatchers.Unconfined + RESUME + BridgeContextContributor.capture(contributors)
        return mono(context) { pipeline.evaluate(read(exchange)) }
            .flatMap { outcome -> write(exchange, outcome, chain) }
    }

    private fun write(
        exchange: ServerWebExchange,
        outcome: Outcome,
        chain: (ServerWebExchange, Outcome) -> Mono<Void>,
    ): Mono<Void> {
        val response = exchange.response
        outcome.headers.forEach { (name, value) -> response.headers.set(name, value) }
        val problem = outcome.problem
        if (problem != null) {
            response.statusCode = HttpStatusCode.valueOf(outcome.status)
            val body = ProblemEncoder.encode(problem)
            // With its length on every server: Reactor Netty sets it for one buffer, the servlet containers would chunk.
            response.headers.contentLength = body.size.toLong()
            return response.writeWith(response.bufferFactory().wrap(body).toMono())
        }
        val securityContext = checkNotNull(outcome.security) { "A proceeding outcome carries its security." }
            .securityContext
        exchange.setSecurityContext(securityContext)
        val authorized = exchange.mutate().principal(securityContext.principal.toMono()).build()
        return chain(authorized, outcome).writeSecurityContext(securityContext)
    }

    private companion object {
        val RESUME = ResumeDispatcher(Dispatchers.Default)
    }
}
