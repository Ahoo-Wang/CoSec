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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.BlacklistVerifyContext
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.NoMatchVerifyContext
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.limiter.RateLimitResult
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration

class MeteredAuthorizationTest {
    private val registry = SimpleMeterRegistry()
    private val request = EvaluateRequest()
    private val context = SimpleSecurityContext(SimplePrincipal("user"))

    private fun deciding(decision: AuthorizationDecision?): Authorization = object : DecisionAuthorization {
        override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
            Mono.justOrEmpty(decision)
    }

    private fun count(result: String, source: String): Long =
        registry.find(MeteredAuthorization.METRIC_NAME)
            .tag(MeteredAuthorization.RESULT_TAG, result)
            .tag(MeteredAuthorization.SOURCE_TAG, source)
            .timer()?.count() ?: 0

    private fun decide(result: AuthorizeResult, verifyContext: VerifyContext?, rateLimit: RateLimitResult? = null) {
        MeteredAuthorization(deciding(AuthorizationDecision(result, verifyContext, rateLimit)), registry)
            .decide(request, context).block()
    }

    @Test
    fun recordsResultAndSource() {
        decide(AuthorizeResult.ALLOW, RootVerifyContext)
        decide(AuthorizeResult.ALLOW, RootVerifyContext)
        decide(AuthorizeResult.EXPLICIT_DENY, BlacklistVerifyContext)
        decide(AuthorizeResult.IMPLICIT_DENY, NoMatchVerifyContext)
        decide(AuthorizeResult.IMPLICIT_DENY, null)
        val exceeded = RateLimitResult(allowed = false, limit = 1, remaining = 0, resetAfter = Duration.ofSeconds(1))
        decide(AuthorizeResult.TOO_MANY_REQUESTS, RootVerifyContext, exceeded)
        count("allow", "root").assert().isEqualTo(2)
        count("explicit_deny", "blacklist").assert().isEqualTo(1)
        count("implicit_deny", "none").assert().isEqualTo(1)
        count("implicit_deny", "unknown").assert().isEqualTo(1)
        count("too_many_requests", "root").assert().isEqualTo(1)
    }

    @Test
    fun emptyCountsAsImplicitDeny() {
        MeteredAuthorization(deciding(null), registry).decide(request, context).test().verifyComplete()
        count("implicit_deny", "unknown").assert().isEqualTo(1)
    }

    @Test
    fun errorsAreRecordedAndPropagated() {
        val failing = Authorization { _, _ -> Mono.error(IllegalStateException("boom")) }
        MeteredAuthorization(failing, registry).authorize(request, context)
            .test().expectError(IllegalStateException::class.java).verify()
        val throwing = Authorization { _, _ -> throw IllegalStateException("boom") }
        MeteredAuthorization(throwing, registry).authorize(request, context)
            .test().expectError(IllegalStateException::class.java).verify()
        count("error", "unknown").assert().isEqualTo(2)
    }

    @Test
    fun passesDecisionThrough() {
        val decision = AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext)
        MeteredAuthorization(deciding(decision), registry).decide(request, context)
            .test().expectNext(decision).verifyComplete()
        MeteredAuthorization(Authorization { _, _ -> AuthorizeResult.ALLOW.toMono() }, registry)
            .authorize(request, context).test().expectNext(AuthorizeResult.ALLOW).verifyComplete()
        count("allow", "unknown").assert().isEqualTo(1)
    }
}
