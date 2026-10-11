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

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.context.RequestSecurityContexts.getRequest
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.InvalidRequestPathException
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class SecurityPipelineTest {
    companion object {
        private const val INVALID_PATH = "/invalid"
        private val requestParser = object : RequestParser<String> {
            override fun parse(request: String): Request {
                if (request == INVALID_PATH) throw InvalidRequestPathException()
                return EvaluateRequest(path = request)
            }
        }
        private val anonymousParser = SecurityContextParser { SimpleSecurityContext.anonymous() }
        private val authenticatedParser = SecurityContextParser { SimpleSecurityContext(SimplePrincipal("user")) }
        private val expiredTokenParser = SecurityContextParser { throw TokenExpiredException() }

        private fun returning(result: AuthorizeResult) = Authorization { _, _ -> result.toMono() }
        private fun failing(error: Throwable) = Authorization { _, _ -> Mono.error(error) }
        private fun throwing(error: Throwable) = Authorization { _, _ -> throw error }

        private fun case(
            name: String,
            parser: SecurityContextParser,
            authorization: Authorization,
            status: SecurityStatus,
            result: AuthorizeResult
        ): Arguments = Arguments.of(name, parser, authorization, status, result)

        private val allow = returning(AuthorizeResult.ALLOW)
        private val implicitDeny = returning(AuthorizeResult.IMPLICIT_DENY)
        private val brokenParser = SecurityContextParser { error("store down") }

        @JvmStatic
        @Suppress("LongMethod") // data table of status-mapping cases
        fun statusCases(): List<Arguments> = listOf(
            case("allowed", anonymousParser, allow, SecurityStatus.ALLOWED, AuthorizeResult.ALLOW),
            case(
                "anonymous denied",
                anonymousParser,
                implicitDeny,
                SecurityStatus.UNAUTHORIZED,
                AuthorizeResult.IMPLICIT_DENY
            ),
            case(
                "authenticated denied",
                authenticatedParser,
                returning(AuthorizeResult.EXPLICIT_DENY),
                SecurityStatus.FORBIDDEN,
                AuthorizeResult.EXPLICIT_DENY
            ),
            case(
                "expired token",
                expiredTokenParser,
                implicitDeny,
                SecurityStatus.UNAUTHORIZED,
                AuthorizeResult.TOKEN_EXPIRED
            ),
            case(
                "empty result",
                authenticatedParser,
                { _, _ -> Mono.empty() },
                SecurityStatus.FORBIDDEN,
                AuthorizeResult.IMPLICIT_DENY
            ),
            case(
                "rate limited",
                anonymousParser,
                failing(TooManyRequestsException()),
                SecurityStatus.TOO_MANY_REQUESTS,
                AuthorizeResult.TOO_MANY_REQUESTS
            ),
            case(
                "regex timeout",
                anonymousParser,
                failing(RegexTimeoutException("timeout")),
                SecurityStatus.FORBIDDEN,
                AuthorizeResult.IMPLICIT_DENY
            ),
            case(
                "unexpected error",
                anonymousParser,
                failing(IllegalStateException()),
                SecurityStatus.ERROR,
                AuthorizeResult.IMPLICIT_DENY
            ),
            case(
                "synchronous throw",
                anonymousParser,
                throwing(TooManyRequestsException()),
                SecurityStatus.TOO_MANY_REQUESTS,
                AuthorizeResult.TOO_MANY_REQUESTS
            ),
            case("context parse error", brokenParser, allow, SecurityStatus.ERROR, AuthorizeResult.IMPLICIT_DENY),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statusCases")
    fun evaluate(
        @Suppress("UNUSED_PARAMETER") name: String,
        securityContextParser: SecurityContextParser,
        authorization: Authorization,
        expectedStatus: SecurityStatus,
        expectedResult: AuthorizeResult
    ) {
        SecurityPipeline(requestParser, securityContextParser, authorization)
            .evaluate("/path")
            .test()
            .consumeNextWith {
                it.status.assert().isEqualTo(expectedStatus)
                it.result.assert().isEqualTo(expectedResult)
                it.request.assert().isNotNull()
            }.verifyComplete()
    }

    @Test
    fun evaluateWhenInvalidPath() {
        SecurityPipeline(requestParser, anonymousParser, returning(AuthorizeResult.ALLOW))
            .evaluate(INVALID_PATH)
            .test()
            .expectNext(SecurityDecision.BAD_REQUEST)
            .verifyComplete()
    }

    @Test
    fun evaluateCarriesSecurityContextAndVerifyContext() {
        val authorization = object : DecisionAuthorization {
            override fun decide(request: Request, context: me.ahoo.cosec.api.context.SecurityContext) =
                AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext).toMono()
        }
        SecurityPipeline(requestParser, authenticatedParser, authorization)
            .evaluate("/path")
            .test()
            .consumeNextWith {
                it.allowed.assert().isTrue()
                it.verifyContext.assert().isEqualTo(RootVerifyContext)
                val securityContext = checkNotNull(it.securityContext)
                securityContext.principal.id.assert().isEqualTo("user")
                securityContext.getRequest<Request>().assert().isSameAs(it.request)
            }.verifyComplete()
    }

    @Test
    fun evaluateParsesSecurityContextOnScheduler() {
        var parseThreadName: String? = null
        val securityContextParser = SecurityContextParser {
            parseThreadName = Thread.currentThread().name
            SimpleSecurityContext.anonymous()
        }
        SecurityPipeline(
            requestParser,
            securityContextParser,
            returning(AuthorizeResult.ALLOW),
            Schedulers.boundedElastic()
        )
            .evaluate("/path")
            .block()
        parseThreadName.assert().startsWith("boundedElastic")
    }
}
