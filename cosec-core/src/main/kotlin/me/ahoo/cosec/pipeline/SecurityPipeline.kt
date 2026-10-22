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

package me.ahoo.cosec.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.decide
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.InvalidRequestPathException
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.cosec.token.TokenVerificationContexts.getTokenVerificationException
import me.ahoo.cosec.token.TokenVerificationContexts.setTokenVerificationException
import me.ahoo.cosec.token.TokenVerificationException
import me.ahoo.cosec.token.toAuthorizeResult
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono

/**
 * Transport-agnostic security pipeline: parse request → resolve security context → authorize → [SecurityDecision].
 *
 * Every outcome, including errors, is mapped to a [SecurityDecision] (fail-closed), so transport adapters
 * (WebFlux, WebMvc, Gateway) only translate the decision into a response.
 *
 * Status mapping:
 * - invalid request path → [SecurityStatus.BAD_REQUEST]
 * - over a rate limit ([AuthorizationDecision.rateLimit] not allowed) → [SecurityStatus.TOO_MANY_REQUESTS]
 * - authorized → [SecurityStatus.ALLOWED]
 * - denied, anonymous principal → [SecurityStatus.UNAUTHORIZED]; authenticated → [SecurityStatus.FORBIDDEN].
 *   A token verification failure replaces the result reason (e.g. Token Expired).
 * - [RegexTimeoutException] (ReDoS guard) → an implicit deny, mapped like any other deny (401 anonymous, 403
 *   authenticated) rather than a 5xx
 * - any other error → [SecurityStatus.ERROR]
 *
 * @param S the transport-specific request type
 * @param securityContextParseScheduler scheduler [securityContextParser] runs on. Defaults to
 * [Schedulers.immediate]; use a blocking-capable scheduler (e.g. [Schedulers.boundedElastic]) when parsing may
 * block, such as a token revocation check backed by Redis.
 */
class SecurityPipeline<S : Any>(
    val requestParser: RequestParser<S>,
    val securityContextParser: SecurityContextParser,
    val authorization: Authorization,
    val securityContextParseScheduler: Scheduler = Schedulers.immediate()
) {
    companion object {
        private val log = KotlinLogging.logger {}

        // internal: DecisionParity (test fixtures) derives the status from this mapping rather than copying it.
        internal fun toDecision(
            request: Request,
            securityContext: SecurityContext,
            decision: AuthorizationDecision
        ): SecurityDecision {
            if (decision.rateLimit?.allowed == false) {
                return SecurityDecision(
                    status = SecurityStatus.TOO_MANY_REQUESTS,
                    result = AuthorizeResult.TOO_MANY_REQUESTS,
                    request = request,
                    securityContext = securityContext,
                    verifyContext = decision.verifyContext,
                    rateLimit = decision.rateLimit,
                )
            }
            if (decision.result.authorized) {
                return SecurityDecision(
                    status = SecurityStatus.ALLOWED,
                    result = decision.result,
                    request = request,
                    securityContext = securityContext,
                    verifyContext = decision.verifyContext,
                    rateLimit = decision.rateLimit,
                )
            }
            val status = if (securityContext.principal.authenticated) {
                SecurityStatus.FORBIDDEN
            } else {
                SecurityStatus.UNAUTHORIZED
            }
            return SecurityDecision(
                status = status,
                result = securityContext.getTokenVerificationException()?.toAuthorizeResult() ?: decision.result,
                request = request,
                securityContext = securityContext,
                verifyContext = decision.verifyContext,
            )
        }

        internal fun toErrorDecision(
            request: Request,
            securityContext: SecurityContext,
            cause: Throwable
        ): SecurityDecision {
            if (cause is RegexTimeoutException) {
                return toDecision(request, securityContext, AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY))
            }
            log.error(cause) {
                "Unexpected error during authorization of request [${request.path}] [${request.method}]."
            }
            return SecurityDecision(SecurityStatus.ERROR, AuthorizeResult.IMPLICIT_DENY, request, securityContext)
        }
    }

    fun evaluate(source: S): Mono<SecurityDecision> = Mono.defer {
        val request = try {
            requestParser.parse(source)
        } catch (_: InvalidRequestPathException) {
            return@defer SecurityDecision.BAD_REQUEST.toMono()
        }
        parseSecurityContext(request)
            .onErrorResume { cause ->
                log.error(cause) {
                    "Unexpected error while parsing security context of request [${request.path}] [${request.method}]."
                }
                Mono.empty()
            }
            .flatMap { securityContext -> authorize(request, securityContext) }
            .switchIfEmpty {
                SecurityDecision(SecurityStatus.ERROR, AuthorizeResult.IMPLICIT_DENY, request).toMono()
            }
    }

    private fun parseSecurityContext(request: Request): Mono<SecurityContext> =
        Mono.fromCallable<SecurityContext> {
            try {
                securityContextParser.parse(request)
            } catch (verificationException: TokenVerificationException) {
                log.debug(verificationException) {
                    "Parse request to security context failed."
                }
                SimpleSecurityContext.anonymous().also {
                    it.setTokenVerificationException(verificationException)
                }
            }.setRequest(request)
        }.subscribeOn(securityContextParseScheduler)

    private fun authorize(request: Request, securityContext: SecurityContext): Mono<SecurityDecision> =
        // defer: an Authorization that throws synchronously must still be mapped to a decision
        Mono.defer { authorization.decide(request, securityContext) }
            .map { toDecision(request, securityContext, it) }
            .switchIfEmpty {
                toDecision(request, securityContext, AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY)).toMono()
            }.onErrorResume { cause ->
                toErrorDecision(request, securityContext, cause).toMono()
            }
}
