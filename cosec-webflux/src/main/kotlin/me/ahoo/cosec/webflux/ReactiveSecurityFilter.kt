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

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.RequestIdCapable.Companion.REQUEST_ID_KEY
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.writeSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toMono

/**
 * Base class for reactive security filters.
 *
 * Runs each exchange through a [SecurityPipeline] and translates the resulting
 * [me.ahoo.cosec.pipeline.SecurityDecision] into a WebFlux response. All authorization and error-mapping
 * logic lives in the pipeline, shared with the servlet integration.
 *
 * @param pipeline the security pipeline evaluating each exchange
 * @see ReactiveAuthorizationFilter
 * @see me.ahoo.cosec.gateway.AuthorizationGatewayFilter
 */
abstract class ReactiveSecurityFilter(
    val pipeline: SecurityPipeline<ServerWebExchange>
) {
    /**
     * @param securityContextParseScheduler see [SecurityPipeline.securityContextParseScheduler]
     */
    constructor(
        securityContextParser: SecurityContextParser,
        requestParser: RequestParser<ServerWebExchange>,
        authorization: Authorization,
        securityContextParseScheduler: Scheduler = Schedulers.immediate()
    ) : this(SecurityPipeline(requestParser, securityContextParser, authorization, securityContextParseScheduler))

    val securityContextParser: SecurityContextParser
        get() = pipeline.securityContextParser
    val requestParser: RequestParser<ServerWebExchange>
        get() = pipeline.requestParser
    val authorization: Authorization
        get() = pipeline.authorization

    fun filterInternal(
        exchange: ServerWebExchange,
        chain: (ServerWebExchange, Request) -> Mono<Void>
    ): Mono<Void> = pipeline.evaluate(exchange).flatMap { decision ->
        val response = exchange.response
        val request = decision.request
        if (request == null) {
            response.statusCode = HttpStatus.valueOf(decision.status.code)
            return@flatMap Mono.empty()
        }
        response.headers.trySet(REQUEST_ID_KEY, request.requestId)
        val securityContext = decision.securityContext
        securityContext?.let { exchange.setSecurityContext(it) }
        if (!decision.allowed) {
            response.statusCode = HttpStatus.valueOf(decision.status.code)
            return@flatMap response.writeWithAuthorizeResult(decision.result)
        }
        checkNotNull(securityContext) { "An allowed decision must carry a SecurityContext." }
        val authorizedExchange = exchange
            .mutate()
            .principal(securityContext.principal.toMono())
            .build()
        chain(authorizedExchange, request).writeSecurityContext(securityContext)
    }

    fun ServerHttpResponse.writeWithAuthorizeResult(authorizeResult: AuthorizeResult): Mono<Void> {
        headers.contentType = MediaType.APPLICATION_JSON
        val responseBodyBytes = CoSecJsonSerializer.writeValueAsBytes(authorizeResult)
        val builder = bufferFactory().wrap(responseBodyBytes).toMono()
        return writeWith(builder)
    }
}
