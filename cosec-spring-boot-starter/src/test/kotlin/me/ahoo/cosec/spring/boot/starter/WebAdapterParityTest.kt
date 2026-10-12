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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.limiter.RateLimitResult
import me.ahoo.cosec.pipeline.SecurityDecision
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.servlet.AuthorizationFilter
import me.ahoo.cosec.servlet.ServletRemoteIpResolver
import me.ahoo.cosec.servlet.ServletRequestParser
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.cosec.webflux.ReactiveAuthorizationFilter
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.time.Duration

/**
 * Drives the WebFlux and WebMvc adapters with the same scenarios and asserts they respond identically.
 */
class WebAdapterParityTest {
    data class Outcome(val status: Int, val body: String, val proceeded: Boolean, val headers: Map<String, String?>)

    companion object {
        private val anonymous = SecurityContextParser { SimpleSecurityContext.anonymous() }
        private val authenticated = SecurityContextParser { SimpleSecurityContext(SimplePrincipal("user")) }
        private val expiredToken = SecurityContextParser { throw TokenExpiredException() }
        private val brokenParser = SecurityContextParser { error("store down") }

        private fun returning(result: AuthorizeResult) = Authorization { _, _ -> result.toMono() }
        private fun failing(error: Throwable) = Authorization { _, _ -> Mono.error(error) }
        private fun rateLimited(result: AuthorizeResult, allowed: Boolean) = object : DecisionAuthorization {
            override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> {
                val rateLimit = RateLimitResult(
                    allowed,
                    limit = 10,
                    remaining = if (allowed) 9 else 0,
                    Duration.ofSeconds(2)
                )
                return AuthorizationDecision(result, rateLimit = rateLimit).toMono()
            }
        }

        private val headerNames = listOf(
            SecurityDecision.RATE_LIMIT_LIMIT_HEADER,
            SecurityDecision.RATE_LIMIT_REMAINING_HEADER,
            SecurityDecision.RATE_LIMIT_RESET_HEADER,
            SecurityDecision.RETRY_AFTER_HEADER,
        )

        @JvmStatic
        fun scenarios(): List<Arguments> = listOf(
            Arguments.of("allowed", "/path", anonymous, returning(AuthorizeResult.ALLOW)),
            Arguments.of("anonymous denied", "/path", anonymous, returning(AuthorizeResult.IMPLICIT_DENY)),
            Arguments.of("authenticated denied", "/path", authenticated, returning(AuthorizeResult.EXPLICIT_DENY)),
            Arguments.of("expired token", "/path", expiredToken, returning(AuthorizeResult.IMPLICIT_DENY)),
            Arguments.of("empty result", "/path", authenticated, Authorization { _, _ -> Mono.empty() }),
            Arguments.of(
                "rate limit exceeded",
                "/path",
                authenticated,
                rateLimited(AuthorizeResult.TOO_MANY_REQUESTS, allowed = false),
            ),
            Arguments.of(
                "within rate limit",
                "/path",
                authenticated,
                rateLimited(AuthorizeResult.ALLOW, allowed = true)
            ),
            Arguments.of("regex timeout", "/path", anonymous, failing(RegexTimeoutException("timeout"))),
            Arguments.of("unexpected error", "/path", anonymous, failing(IllegalStateException())),
            Arguments.of("context parse error", "/path", brokenParser, returning(AuthorizeResult.ALLOW)),
            Arguments.of("invalid path", "/public/../admin", anonymous, returning(AuthorizeResult.ALLOW)),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    fun adaptersRespondIdentically(
        @Suppress("UNUSED_PARAMETER") name: String,
        path: String,
        securityContextParser: SecurityContextParser,
        authorization: Authorization
    ) {
        val reactive = reactiveOutcome(path, securityContextParser, authorization)
        val servlet = servletOutcome(path, securityContextParser, authorization)
        servlet.assert().isEqualTo(reactive)
    }

    @Test
    fun rateLimitExceededWritesRetryAfter() {
        val outcome = servletOutcome("/path", authenticated, rateLimited(AuthorizeResult.TOO_MANY_REQUESTS, false))
        outcome.status.assert().isEqualTo(429)
        outcome.proceeded.assert().isFalse()
        outcome.headers.assert().isEqualTo(
            mapOf(
                SecurityDecision.RATE_LIMIT_LIMIT_HEADER to "10",
                SecurityDecision.RATE_LIMIT_REMAINING_HEADER to "0",
                SecurityDecision.RATE_LIMIT_RESET_HEADER to "2",
                SecurityDecision.RETRY_AFTER_HEADER to "2",
            ),
        )
    }

    private fun reactiveOutcome(
        path: String,
        securityContextParser: SecurityContextParser,
        authorization: Authorization
    ): Outcome {
        val filter = ReactiveAuthorizationFilter(
            securityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            authorization,
        )
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path))
        var proceeded = false
        filter.filter(exchange) {
            proceeded = true
            Mono.empty()
        }.then(Mono.defer { exchange.response.setComplete() }).block()
        val response = exchange.response
        return Outcome(
            status = response.statusCode?.value() ?: 200,
            body = response.bodyAsString.block().orEmpty(),
            proceeded = proceeded,
            headers = headerNames.associateWith { response.headers.getFirst(it) },
        )
    }

    private fun servletOutcome(
        path: String,
        securityContextParser: SecurityContextParser,
        authorization: Authorization
    ): Outcome {
        val filter = AuthorizationFilter(
            securityContextParser,
            authorization,
            ServletRequestParser(ServletRemoteIpResolver),
        )
        val request = MockHttpServletRequest("GET", path).apply { servletPath = path }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return Outcome(
            status = response.status,
            body = response.contentAsString,
            proceeded = chain.request != null,
            headers = headerNames.associateWith { response.getHeader(it) },
        )
    }
}
