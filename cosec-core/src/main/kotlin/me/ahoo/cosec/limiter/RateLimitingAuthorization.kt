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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.decide
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * [Authorization] decorator that applies rate limits to the requests [delegate] allows.
 *
 * Authorization stays a pure decision, and only allowed requests spend permits: a denied request is already
 * rejected and must not use up the quota of legitimate ones. Every matching rule spends one permit, in order,
 * and the first exhausted rule turns the decision into [AuthorizeResult.TOO_MANY_REQUESTS], which
 * `SecurityPipeline` maps to `429`. The tightest [RateLimitResult] travels on the decision so transports can
 * write rate limit headers.
 */
class RateLimitingAuthorization(
    override val delegate: Authorization,
    private val ruleProvider: RateLimitRuleProvider,
    private val rateLimiter: RateLimiter
) : DecisionAuthorization,
    Delegated<Authorization> {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Emit at most one failure log per interval to avoid a log storm during a backend outage. */
        private const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }

    @Volatile
    private var lastFailureLogAt = 0L

    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
        Mono.defer { delegate.decide(request, context) }
            .flatMap { decision ->
                if (!decision.result.authorized) {
                    return@flatMap decision.toMono()
                }
                val matchedRules = ruleProvider.getRules().filter { it.matches(request, context) }
                if (matchedRules.isEmpty()) {
                    return@flatMap decision.toMono()
                }
                acquire(matchedRules, 0, request, context, decision)
            }

    private fun acquire(
        rules: List<RateLimitRule>,
        index: Int,
        request: Request,
        context: SecurityContext,
        decision: AuthorizationDecision
    ): Mono<AuthorizationDecision> {
        if (index == rules.size) {
            return decision.toMono()
        }
        val rule = rules[index]
        // Outside the failure handler: a rule that cannot resolve its key is a configuration error and fails
        // closed, rather than being skipped like an unavailable backend.
        val bucket = rule.bucketOf(request, context)
        return Mono.defer { rateLimiter.tryAcquire(rule.id, bucket, rule.rateLimit) }
            .onErrorResume { cause -> onFailure(rule, cause) }
            .map<AuthorizationDecision> { result ->
                if (result.allowed) {
                    decision.copy(rateLimit = tighter(decision.rateLimit, result))
                } else {
                    log.debug { "Request [$request] exceeded rate limit rule [${rule.id}]." }
                    AuthorizationDecision(AuthorizeResult.TOO_MANY_REQUESTS, decision.verifyContext, result)
                }
            }
            .defaultIfEmpty(decision)
            .flatMap { next ->
                if (next.rateLimit?.allowed == false) {
                    next.toMono()
                } else {
                    acquire(rules, index + 1, request, context, next)
                }
            }
    }

    /**
     * A strict rule fails closed with a denied result; a lenient one is skipped (completes empty).
     */
    private fun onFailure(rule: RateLimitRule, cause: Throwable): Mono<RateLimitResult> {
        logFailure(rule, cause)
        if (!rule.strictFailure) {
            return Mono.empty()
        }
        return rule.rateLimit.resultOf(allowed = false, estimate = 0.0, nowMillis = System.currentTimeMillis())
            .toMono()
    }

    private fun logFailure(rule: RateLimitRule, cause: Throwable) {
        val nowMillis = System.currentTimeMillis()
        if (nowMillis - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) {
            return
        }
        lastFailureLogAt = nowMillis
        if (rule.strictFailure) {
            log.error(cause) { "Rate limiter unavailable - rule [${rule.id}] fails closed." }
        } else {
            log.warn(cause) { "Rate limiter unavailable - rule [${rule.id}] is skipped." }
        }
    }

    private fun tighter(current: RateLimitResult?, candidate: RateLimitResult): RateLimitResult =
        if (current == null || candidate.remaining < current.remaining) candidate else current
}
