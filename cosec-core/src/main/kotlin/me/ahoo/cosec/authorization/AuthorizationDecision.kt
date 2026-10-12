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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.limiter.RateLimitResult
import reactor.core.publisher.Mono

/**
 * The outcome of an authorization together with what produced it.
 *
 * @param result the authorization result
 * @param verifyContext what matched (policy statement, role permission, root, blacklist, ...),
 * or null when the [Authorization] does not report it
 * @param rateLimit the tightest rate limit applied to the request, see [me.ahoo.cosec.limiter.RateLimitingAuthorization];
 * when not [RateLimitResult.allowed], the request exceeded it and [result] is [AuthorizeResult.TOO_MANY_REQUESTS]
 */
data class AuthorizationDecision(
    val result: AuthorizeResult,
    val verifyContext: VerifyContext? = null,
    val rateLimit: RateLimitResult? = null
)

/**
 * An [Authorization] that reports the full [AuthorizationDecision] instead of only the [AuthorizeResult].
 *
 * Decorators should implement this interface and forward [decide] so the decision flows through the chain.
 */
interface DecisionAuthorization : Authorization {
    fun decide(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizationDecision>

    override fun authorize(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizeResult> = decide(request, context).map { it.result }
}

/**
 * Decides with [DecisionAuthorization.decide] when supported, otherwise falls back to
 * [Authorization.authorize] without a [VerifyContext].
 */
fun Authorization.decide(
    request: Request,
    context: SecurityContext
): Mono<AuthorizationDecision> {
    if (this is DecisionAuthorization) {
        return decide(request, context)
    }
    return authorize(request, context).map { AuthorizationDecision(it) }
}
