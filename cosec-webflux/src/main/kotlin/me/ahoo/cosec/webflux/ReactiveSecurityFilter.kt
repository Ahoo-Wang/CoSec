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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.token.TokenVerificationException
import me.ahoo.cosec.token.asAuthorizeResult
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.writeSecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

abstract class ReactiveSecurityFilter(
    val securityContextParser: SecurityContextParser,
    val requestParser: RequestParser<ServerWebExchange>,
    val authorization: Authorization
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    fun filterInternal(
        exchange: ServerWebExchange,
        chain: (ServerWebExchange) -> Mono<Void>
    ): Mono<Void> {
        val request = requestParser.parse(exchange)
        var tokenVerificationException: TokenVerificationException? = null
        val securityContext = try {
            securityContextParser.parse(request)
        } catch (verificationException: TokenVerificationException) {
            log.debug(verificationException) {
                "Parse request to security context failed."
            }
            tokenVerificationException = verificationException
            SimpleSecurityContext.anonymous()
        }
        securityContext.setRequest(request)
        exchange.setSecurityContext(securityContext)
        return authorization.authorize(request, securityContext)
            .flatMap { authorizeResult ->
                if (authorizeResult.authorized) {
                    exchange.mutate()
                        .principal(securityContext.principal.toMono())
                        .build().let {
                            return@flatMap chain(it).writeSecurityContext(securityContext)
                        }
                }
                val principal = securityContext.principal
                if (!principal.authenticated()) {
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                } else {
                    exchange.response.statusCode = HttpStatus.FORBIDDEN
                }
                exchange.response.writeWithAuthorizeResult(
                    tokenVerificationException?.asAuthorizeResult() ?: authorizeResult,
                )
            }.onErrorResume(TooManyRequestsException::class.java) { _ ->
                exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                exchange.response.writeWithAuthorizeResult(AuthorizeResult.TOO_MANY_REQUESTS)
            }
    }

    fun ServerHttpResponse.writeWithAuthorizeResult(authorizeResult: AuthorizeResult): Mono<Void> {
        headers.contentType = MediaType.APPLICATION_JSON
        val responseBodyBytes = CoSecJsonSerializer.writeValueAsBytes(authorizeResult)
        val builder = bufferFactory().wrap(responseBodyBytes).toMono()
        return writeWith(builder)
    }
}
