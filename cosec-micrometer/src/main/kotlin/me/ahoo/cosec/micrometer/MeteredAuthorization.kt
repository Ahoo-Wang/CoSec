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

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.BlacklistVerifyContext
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.NoMatchVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifySource
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.authorization.decide
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

/**
 * Records every decision of [delegate] as the timer [METRIC_NAME], tagged with its [RESULT_TAG] and [SOURCE_TAG].
 *
 * Both tags have a small fixed set of values, so the number of time series stays bounded however many policies
 * and principals there are. A sudden rise of `explicit_deny` or `error` is often an attack or a broken policy.
 */
class MeteredAuthorization(
    override val delegate: Authorization,
    private val registry: MeterRegistry
) : DecisionAuthorization,
    Delegated<Authorization> {
    companion object {
        const val METRIC_NAME = "cosec.authorization"
        const val RESULT_TAG = "result"
        const val SOURCE_TAG = "source"

        const val ALLOW = "allow"
        const val EXPLICIT_DENY = "explicit_deny"
        const val IMPLICIT_DENY = "implicit_deny"
        const val TOO_MANY_REQUESTS = "too_many_requests"
        const val ERROR = "error"
        const val UNKNOWN = "unknown"
    }

    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
        Mono.defer {
            val startNanos = System.nanoTime()
            Mono.defer { delegate.decide(request, context) }
                .doOnSuccess { decision ->
                    record(startNanos, resultOf(decision), decision?.verifyContext.sourceTag())
                }
                .doOnError { record(startNanos, ERROR, UNKNOWN) }
        }

    private fun resultOf(decision: AuthorizationDecision?): String = when {
        decision == null -> IMPLICIT_DENY
        decision.rateLimit?.allowed == false -> TOO_MANY_REQUESTS
        decision.result.authorized -> ALLOW
        decision.result.reason == AuthorizeResult.IMPLICIT_DENY.reason -> IMPLICIT_DENY
        else -> EXPLICIT_DENY
    }

    private fun VerifyContext?.sourceTag(): String = when (this) {
        is PolicyVerifyContext -> when (source) {
            PolicyVerifySource.GLOBAL -> "global_policy"
            PolicyVerifySource.PRINCIPAL -> "principal_policy"
        }

        is RoleVerifyContext -> "role_permission"
        RootVerifyContext -> "root"
        BlacklistVerifyContext -> "blacklist"
        NoMatchVerifyContext -> "none"
        else -> UNKNOWN
    }

    private fun record(startNanos: Long, result: String, source: String) {
        Timer.builder(METRIC_NAME)
            .description("CoSec authorization decisions")
            .tag(RESULT_TAG, result)
            .tag(SOURCE_TAG, source)
            .register(registry)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
    }
}
