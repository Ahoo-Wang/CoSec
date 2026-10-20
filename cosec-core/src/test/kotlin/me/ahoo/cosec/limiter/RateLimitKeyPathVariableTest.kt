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

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RFC 0001, D1: a rule keyed by a path variable must count in the bucket of its own match, whatever other rules
 * match the same request.
 *
 * Path matchers write their variables into the shared security context, and [RateLimitingAuthorization] matches
 * every rule before it resolves any bucket. A later matching rule therefore replaces the variables the keyed rule
 * reads.
 */
class RateLimitKeyPathVariableTest {
    private val acquired = CopyOnWriteArrayList<String>()
    private val localRateLimiter = LocalRateLimiter(clock = { 0 })
    private val rateLimiter = RateLimiter { ruleId, bucket, rateLimit ->
        acquired.add("$ruleId/$bucket")
        localRateLimiter.tryAcquire(ruleId, bucket, rateLimit)
    }

    private val allowing = object : DecisionAuthorization {
        override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
            AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext).toMono()
    }

    private fun rule(id: String, path: String, permitsPerSecond: Double, key: String? = null) = RateLimitRule(
        id = id,
        action = PathActionMatcherFactory().create(path.asConfiguration()),
        rateLimit = RateLimit(permitsPerSecond = permitsPerSecond),
        key = key,
    )

    private val perOrder =
        rule("per-order", "/orders/{orderId}", permitsPerSecond = 1.0, key = "request.path.var.orderId")
    private val allOrders = rule("all-orders", "/orders/**", permitsPerSecond = 100.0)

    private fun decide(authorization: RateLimitingAuthorization, path: String): AuthorizationDecision {
        val context = SimpleSecurityContext(SimplePrincipal("user"))
        return checkNotNull(authorization.decide(EvaluateRequest(path = path, method = "GET"), context).block())
    }

    @Test
    fun keyedRuleAloneCountsPerPathVariable() {
        // Control: with the keyed rule alone, each order gets its own bucket.
        val authorization = RateLimitingAuthorization(allowing, RateLimitRuleProvider.of(listOf(perOrder)), rateLimiter)
        decide(authorization, "/orders/1")
        decide(authorization, "/orders/2")
        acquired.assert().containsExactly("per-order/g:1", "per-order/g:2")
    }

    @Test
    fun keyedRuleKeepsItsBucketWhenAnotherRuleAlsoMatches() {
        val rules = RateLimitRuleProvider.of(listOf(perOrder, allOrders))
        val authorization = RateLimitingAuthorization(allowing, rules, rateLimiter)

        decide(authorization, "/orders/1")
        acquired.assert().contains("per-order/g:1")
    }

    @Test
    fun differentOrdersAreNotThrottledTogether() {
        val rules = RateLimitRuleProvider.of(listOf(perOrder, allOrders))
        val authorization = RateLimitingAuthorization(allowing, rules, rateLimiter)

        decide(authorization, "/orders/1").result.assert().isEqualTo(AuthorizeResult.ALLOW)
        // One permit per order per second: a different order must still be allowed.
        decide(authorization, "/orders/2").result.assert().isEqualTo(AuthorizeResult.ALLOW)
    }
}
