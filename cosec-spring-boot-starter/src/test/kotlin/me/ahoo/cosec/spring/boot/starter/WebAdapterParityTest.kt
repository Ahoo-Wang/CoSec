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
import me.ahoo.cosec.context.request.RequestAttributesAppender
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.util.UriUtils
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.net.URI
import java.time.Duration

/**
 * Drives the WebFlux and WebMvc adapters with the same scenarios and asserts they respond identically.
 */
class WebAdapterParityTest {
    data class Outcome(
        val status: Int,
        val body: String,
        val proceeded: Boolean,
        val headers: Map<String, String?>,
        val authorizedPath: String?
    )

    /** Records the path the policies see, after the adapter normalized it. */
    private class PathRecorder : RequestAttributesAppender {
        var path: String? = null
        override fun append(request: Request): Request = request.also { path = it.path }
    }

    /** Records what the policies see for a few query keys, the app and space id fallbacks included. */
    private class QueryRecorder : RequestAttributesAppender {
        var seen: Map<String, String>? = null
        override fun append(request: Request): Request = request.also {
            seen = QUERY_KEYS.associateWith { key -> request.getQuery(key) } +
                mapOf("appId" to request.appId, "spaceId" to request.spaceId)
        }
    }

    companion object {
        private val QUERY_KEYS = listOf("a", "b", "a[0]", "CoSec-App-Id", "CoSec-Space-Id")
        private const val FORM_BODY = "CoSec-App-Id=fromBody&a=fromBody"

        @JvmStatic
        fun queries(): List<String> = listOf(
            "",
            "a=1",
            "a=1&a=2",
            "a=&a=2",
            "a&a=2",
            "a=x%20y+z&b=%E4%BD%A0",
            "a%5B0%5D=v",
            "a=b=c",
            "a=%2B&&b=2",
            "CoSec-App-Id=app&CoSec-Space-Id=space",
        )

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

        private val viewer =
            SecurityContextParser { SimpleSecurityContext(SimplePrincipal("user", roles = setOf("viewer"))) }
        private val missingPolicy = SecurityContextParser {
            SimpleSecurityContext(SimplePrincipal("user", policies = setOf("missing")))
        }

        @JvmStatic
        fun implementationScenarios(): List<Arguments> = listOf(
            Arguments.of("global allow", "/public/page", anonymous, 200),
            Arguments.of("global deny, anonymous", "/admin/page", anonymous, 401),
            Arguments.of("global deny, authenticated", "/admin/page", authenticated, 403),
            Arguments.of("implicit deny", "/other", authenticated, 403),
            Arguments.of("role allow", "/orders/1", viewer, 200),
            Arguments.of("role deny wins over global allow", "/public/internal", viewer, 403),
            Arguments.of("regex timeout in a condition", "/slow/page", authenticated, 403),
            Arguments.of("matcher error", "/broken/page", authenticated, 500),
            Arguments.of("missing principal policy", "/public/page", missingPolicy, 500),
            Arguments.of("traversal", "/public/../admin/page", anonymous, 400),
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
            Arguments.of(
                "regex timeout, authenticated",
                "/path",
                authenticated,
                failing(RegexTimeoutException("timeout"))
            ),
            Arguments.of("unexpected error", "/path", anonymous, failing(IllegalStateException())),
            Arguments.of("context parse error", "/path", brokenParser, returning(AuthorizeResult.ALLOW)),
            Arguments.of("invalid path", "/public/../admin", anonymous, returning(AuthorizeResult.ALLOW)),
            Arguments.of("path parameters", "/admin;jsessionid=1/users", anonymous, returning(AuthorizeResult.ALLOW)),
            Arguments.of(
                "traversal behind a path parameter",
                "/public/..;/admin",
                anonymous,
                returning(AuthorizeResult.ALLOW)
            ),
            Arguments.of("encoded path", "/orders/a%20b", anonymous, returning(AuthorizeResult.ALLOW)),
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

    @ParameterizedTest(name = "?{0}")
    @MethodSource("queries")
    fun adaptersReadQueriesIdentically(query: String) {
        val servlet = servletQuery("GET", query, form = false)
        servlet.assert().isEqualTo(reactiveQuery("GET", query, form = false))
    }

    @ParameterizedTest(name = "?{0}")
    @MethodSource("queries")
    fun formBodyIsNeverAQuery(query: String) {
        val servlet = servletQuery("POST", query, form = true)
        servlet.assert().isEqualTo(reactiveQuery("POST", query, form = true))
        servlet.assert().isEqualTo(servletQuery("POST", query, form = false))
    }

    /**
     * The real implementations behind both adapters: each scenario must give the same outcome on every adapter and
     * for both implementations (RFC 0002 §8.2), including the error paths the pipeline maps.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementationScenarios")
    fun implementationsRespondIdentically(
        @Suppress("UNUSED_PARAMETER") name: String,
        path: String,
        securityContextParser: SecurityContextParser,
        status: Int
    ) {
        val outcomes = AuthorizationImpl.entries.flatMap { impl ->
            val authorization = RealPolicies.authorization(impl)
            listOf(
                reactiveOutcome(path, securityContextParser, authorization),
                servletOutcome(path, securityContextParser, authorization),
            )
        }
        outcomes.distinct().assert().hasSize(1)
        outcomes.first().status.assert().isEqualTo(status)
    }

    @Test
    fun regexTimeoutDistinguishesAnonymousCallers() {
        servletOutcome("/path", anonymous, failing(RegexTimeoutException("timeout"))).status.assert().isEqualTo(401)
        servletOutcome("/path", authenticated, failing(RegexTimeoutException("timeout"))).status.assert().isEqualTo(403)
    }

    @Test
    fun pathParametersAreRemovedBeforeAuthorization() {
        servletOutcome("/admin;jsessionid=1/users", anonymous, returning(AuthorizeResult.ALLOW))
            .authorizedPath.assert().isEqualTo("/admin/users")
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
        val recorder = PathRecorder()
        val filter = ReactiveAuthorizationFilter(
            securityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver, listOf(recorder)),
            authorization,
        )
        // URI.create keeps the path as sent; a URI template would encode `%` again.
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)))
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
            authorizedPath = recorder.path,
        )
    }

    private fun servletOutcome(
        path: String,
        securityContextParser: SecurityContextParser,
        authorization: Authorization
    ): Outcome {
        val recorder = PathRecorder()
        val filter = AuthorizationFilter(
            securityContextParser,
            authorization,
            ServletRequestParser(ServletRemoteIpResolver, listOf(recorder)),
        )
        // Mapped under `/` like the default DispatcherServlet: the container puts the decoded path in servletPath.
        val request = MockHttpServletRequest("GET", path).apply { servletPath = UriUtils.decode(path, Charsets.UTF_8) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return Outcome(
            status = response.status,
            body = response.contentAsString,
            proceeded = chain.request != null,
            headers = headerNames.associateWith { response.getHeader(it) },
            authorizedPath = recorder.path,
        )
    }

    private fun reactiveQuery(method: String, query: String, form: Boolean): Map<String, String>? {
        val recorder = QueryRecorder()
        val filter = ReactiveAuthorizationFilter(
            anonymous,
            ReactiveRequestParser(ReactiveRemoteIpResolver, listOf(recorder)),
            returning(AuthorizeResult.ALLOW),
        )
        val uri = URI.create(if (query.isEmpty()) "/path" else "/path?$query")
        val builder = MockServerHttpRequest.method(HttpMethod.valueOf(method), uri)
        val request = if (form) {
            builder.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(FORM_BODY)
        } else {
            builder.build()
        }
        filter.filter(MockServerWebExchange.from(request)) { Mono.empty() }.block()
        return recorder.seen
    }

    private fun servletQuery(method: String, query: String, form: Boolean): Map<String, String>? {
        val recorder = QueryRecorder()
        val filter = AuthorizationFilter(
            anonymous,
            returning(AuthorizeResult.ALLOW),
            ServletRequestParser(ServletRemoteIpResolver, listOf(recorder)),
        )
        val request = MockHttpServletRequest(method, "/path").apply {
            servletPath = "/path"
            queryString = query.ifEmpty { null }
            if (form) {
                contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
                setContent(FORM_BODY.toByteArray())
                // What a container's getParameter returns once it parsed the body.
                addParameter("CoSec-App-Id", "fromBody")
                addParameter("a", "fromBody")
            }
        }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        return recorder.seen
    }
}
