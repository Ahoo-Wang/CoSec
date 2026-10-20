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

package me.ahoo.cosec.limiter

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList

class RateLimitingAuthorizationTest {
    private val request = EvaluateRequest(path = "/orders", remoteIp = "1.1.1.1")
    private val context = SimpleSecurityContext(SimplePrincipal("user"))
    private val acquired = CopyOnWriteArrayList<String>()
    private val localRateLimiter = LocalRateLimiter(clock = { 0 })

    /** Records every acquisition and delegates to an in-memory limiter. */
    private val rateLimiter = RateLimiter { ruleId, bucket, rateLimit ->
        acquired.add("$ruleId/$bucket")
        localRateLimiter.tryAcquire(ruleId, bucket, rateLimit)
    }

    private fun deciding(result: AuthorizeResult): Authorization = object : DecisionAuthorization {
        override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
            AuthorizationDecision(result, RootVerifyContext).toMono()
    }

    private fun rule(
        id: String,
        permitsPerSecond: Double = 2.0,
        path: String? = null,
        key: String? = null,
        strictFailure: Boolean = false
    ) = RateLimitRule(
        id = id,
        action = path?.let { PathActionMatcherFactory().create(it.asConfiguration()) } ?: AllActionMatcher.INSTANCE,
        rateLimit = RateLimit(permitsPerSecond = permitsPerSecond),
        key = key,
        strictFailure = strictFailure,
    )

    private fun authorization(
        vararg rules: RateLimitRule,
        delegateResult: AuthorizeResult = AuthorizeResult.ALLOW,
        limiter: RateLimiter = rateLimiter
    ) = RateLimitingAuthorization(deciding(delegateResult), RateLimitRuleProvider.of(rules.toList()), limiter)

    private fun RateLimitingAuthorization.decideOnce(): AuthorizationDecision =
        checkNotNull(decide(request, context).block())

    @Test
    fun deniedRequestsSpendNoPermits() {
        val authorization = authorization(rule("a"), delegateResult = AuthorizeResult.EXPLICIT_DENY)
        authorization.decideOnce().result.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        acquired.assert().isEmpty()
    }

    @Test
    fun noRulesKeepsDecision() {
        val decision = authorization().decideOnce()
        decision.result.assert().isEqualTo(AuthorizeResult.ALLOW)
        decision.rateLimit.assert().isNull()
    }

    @Test
    fun unmatchedRuleSpendsNoPermit() {
        authorization(rule("a", path = "/users")).decideOnce().rateLimit.assert().isNull()
        acquired.assert().isEmpty()
    }

    @Test
    fun allowedCarriesTightestResult() {
        val decision = authorization(rule("loose", permitsPerSecond = 100.0), rule("tight")).decideOnce()
        decision.result.assert().isEqualTo(AuthorizeResult.ALLOW)
        decision.verifyContext.assert().isEqualTo(RootVerifyContext)
        val rateLimit = checkNotNull(decision.rateLimit)
        rateLimit.limit.assert().isEqualTo(2)
        rateLimit.remaining.assert().isEqualTo(1)
        acquired.assert().containsExactly("loose/global", "tight/global")
    }

    @Test
    fun exceededBecomesTooManyRequestsAndStops() {
        val authorization = authorization(rule("tight"), rule("next", permitsPerSecond = 100.0))
        repeat(2) {
            authorization.decideOnce().result.assert().isEqualTo(AuthorizeResult.ALLOW)
        }
        acquired.clear()
        val decision = authorization.decideOnce()
        decision.result.assert().isEqualTo(AuthorizeResult.TOO_MANY_REQUESTS)
        decision.verifyContext.assert().isEqualTo(RootVerifyContext)
        checkNotNull(decision.rateLimit).allowed.assert().isFalse()
        // The first exhausted rule ends evaluation; later rules spend nothing.
        acquired.assert().containsExactly("tight/global")
    }

    @Test
    fun keyedRuleUsesRequestPart() {
        authorization(rule("a", key = "request.remoteIp")).decideOnce()
        acquired.assert().containsExactly("a/g:1.1.1.1")
    }

    @Test
    fun strictFailureFailsClosed() {
        val failing = RateLimiter { _, _, _ -> Mono.error(IllegalStateException("down")) }
        val decision = authorization(rule("a", strictFailure = true), limiter = failing).decideOnce()
        decision.result.assert().isEqualTo(AuthorizeResult.TOO_MANY_REQUESTS)
        checkNotNull(decision.rateLimit).allowed.assert().isFalse()
    }

    @Test
    fun lenientFailureSkipsRule() {
        val failing = RateLimiter { ruleId, bucket, rateLimit ->
            if (ruleId == "broken") {
                Mono.error(
                    IllegalStateException("down")
                )
            } else {
                rateLimiter.tryAcquire(ruleId, bucket, rateLimit)
            }
        }
        val decision = authorization(rule("broken"), rule("ok"), limiter = failing).decideOnce()
        decision.result.assert().isEqualTo(AuthorizeResult.ALLOW)
        checkNotNull(decision.rateLimit).limit.assert().isEqualTo(2)
    }

    @Test
    fun synchronousLimiterErrorIsHandled() {
        val throwing = RateLimiter { _, _, _ -> throw IllegalStateException("down") }
        authorization(rule("a"), limiter = throwing).decideOnce().result.assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun ruleErrorFailsClosed() {
        authorization(rule("a", key = "unsupported.part"))
            .decide(request, context)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun ruleErrorIsRaisedWhenItsRuleIsReached() {
        val authorization = authorization(
            rule("tight", permitsPerSecond = 1.0),
            rule("broken", key = "unsupported.part")
        )
        authorization.decide(request, context)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
        // Rules before the broken one spend their permits first, and an exhausted one answers before it.
        acquired.assert().containsExactly("tight/global")
        authorization.decideOnce().result.assert().isEqualTo(AuthorizeResult.TOO_MANY_REQUESTS)
    }

    @Test
    fun authorize() {
        authorization(rule("a"))
            .authorize(request, context)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }
}
