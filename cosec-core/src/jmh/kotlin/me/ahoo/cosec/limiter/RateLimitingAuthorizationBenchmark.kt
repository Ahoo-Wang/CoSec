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
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.principal.SimplePrincipal
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import reactor.core.publisher.Mono

/**
 * Overhead of [RateLimitingAuthorization] on top of an authorization that always allows.
 *
 * Scenarios:
 * - `plain`: the delegate alone, the baseline
 * - `noRules`: wrapped, with no rules
 * - `unmatched`: wrapped, with rules whose actions do not match
 * - `local`: wrapped, with one matching per-IP rule on [LocalRateLimiter]
 */
@State(Scope.Benchmark)
open class RateLimitingAuthorizationBenchmark {
    @Param("plain", "noRules", "unmatched", "local")
    lateinit var scenario: String

    private lateinit var authorization: Authorization
    private val request = EvaluateRequest(path = "/orders/1", remoteIp = "10.0.0.1")
    private val securityContext = SimpleSecurityContext(SimplePrincipal("user"))

    private fun rule(id: String, pattern: String) = RateLimitRule(
        id = id,
        action = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration()),
        rateLimit = RateLimit(permitsPerSecond = 1_000_000_000.0),
        key = "request.remoteIp",
    )

    @Setup
    fun setup() {
        val decision = Mono.just(AuthorizationDecision(AuthorizeResult.ALLOW, RootVerifyContext))
        val allow = object : DecisionAuthorization {
            override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> = decision
        }
        val rules = when (scenario) {
            "unmatched" -> listOf(rule("users", "/users/**"), rule("exports", "/exports/**"))
            "local" -> listOf(rule("orders", "/orders/**"))
            else -> emptyList()
        }
        authorization = if (scenario == "plain") {
            allow
        } else {
            RateLimitingAuthorization(allow, RateLimitRuleProvider.of(rules), LocalRateLimiter())
        }
    }

    @Benchmark
    fun authorize(): AuthorizeResult? = authorization.authorize(request, securityContext).block()
}
