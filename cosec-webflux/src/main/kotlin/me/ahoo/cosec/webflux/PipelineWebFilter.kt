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

package me.ahoo.cosec.webflux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.ProblemEncoder
import me.ahoo.cosec.runtime.ResumeDispatcher
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.writeSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.core.Ordered
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/** Reads a WebFlux exchange into the facts the pipeline decides on (RFC 0006 §5.3.1), once, without blocking. */
object HttpFactsReader {
    fun read(
        exchange: ServerWebExchange,
        remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
        host: HostScope = NO_HOST,
        requestAttributes: Map<String, String> = emptyMap(),
    ): HttpFacts {
        val request = exchange.request
        val headers = HashMap<String, List<String>>(request.headers.size())
        request.headers.forEach { name, values -> headers[name] = values }
        val cookies = request.cookies.mapValues { (_, values) -> values.map { it.value } }
        return HttpFacts(
            method = request.method.name(),
            // Encoded, and within the application: the context path is not the app's to authorize.
            rawPath = request.path.pathWithinApplication().value(),
            rawQuery = request.uri.rawQuery,
            headers = Headers.of(headers),
            cookies = Cookies.of(cookies),
            remoteAddress = remoteIpResolver.resolve(exchange),
            host = host,
            requestAttributes = requestAttributes,
        )
    }

    val NO_HOST: HostScope = HostScope(null)
}

/**
 * The bridge of the reactive hosts to the coroutine pipeline (RFC 0006 §7.3), shared by [PipelineWebFilter] and the
 * gateway's filter. It translates and never decides: it writes the [Outcome]'s status, headers and problem body, and
 * calls the chain only when the outcome proceeds.
 *
 * The request starts on the event loop without a dispatch (`Dispatchers.Unconfined`) and, after a real suspension,
 * goes on on `Dispatchers.Default` ([ResumeDispatcher]), never on a client library's I/O thread. A client that goes
 * away cancels the subscription, and so the coroutine: no outcome exists, and nothing is written.
 */
abstract class ReactivePipelineBridge(
    val pipeline: Pipeline,
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
            return response.writeWith(response.bufferFactory().wrap(ProblemEncoder.encode(problem)).toMono())
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

/** The WebFlux filter over the coroutine pipeline (RFC 0006 §7.3). */
class PipelineWebFilter(
    pipeline: Pipeline,
    contributors: List<BridgeContextContributor> = emptyList(),
    remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
    host: HostScope = HttpFactsReader.NO_HOST,
) : ReactivePipelineBridge(pipeline, contributors, remoteIpResolver, host),
    WebFilter,
    Ordered {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        filterInternal(exchange) { authorized, _ -> chain.filter(authorized) }

    override fun getOrder(): Int = ReactiveAuthorizationFilter.REACTIVE_AUTHORIZATION_FILTER_ORDER
}

/** [AuthorizationChecker.check] for reactive callers (RFC 0006 §7.6): resumes off the caller's I/O thread. */
fun AuthorizationChecker.checkMono(security: SecurityContext, action: String, resource: String): Mono<Boolean> =
    mono(Dispatchers.Unconfined + ResumeDispatcher(Dispatchers.Default)) { check(security, action, resource) }
