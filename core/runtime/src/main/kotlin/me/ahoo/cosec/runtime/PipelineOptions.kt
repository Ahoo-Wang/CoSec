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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.TraceLevel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the [Pipeline] bounds and reads a request. The defaults are RFC 0006 Q25's; the starter sets them from the
 * `cosec.pipeline.*` properties. Each stage's timeout defaults to its share of [timeout] ([StageBudgets], `PIP-33`):
 * the starter and the builder set the deadline only, and only CoSec's own tests set a stage apart from it.
 */
@InternalCoSecApi
class PipelineOptions(
    /** The whole evaluation (`cosec.pipeline.timeout`): an upper bound, not the sum of the stages. Then 503. */
    val timeout: Duration = 5.seconds,
    /** One [me.ahoo.cosec.spi.authn.Authenticator] call ([StageBudgets.authentication]). Then 503. */
    val authenticationTimeout: Duration = StageBudgets.of(timeout).authentication,
    /** One `MembershipProvider` call ([StageBudgets.membership], RFC 0004). Then 503. */
    val membershipTimeout: Duration = StageBudgets.of(timeout).membership,
    /**
     * One `AttributeProvider` call that sets no timeout of its own ([StageBudgets.attribute], RFC 0006 §5.4.2). Then
     * its attributes are unavailable.
     */
    val attributeTimeout: Duration = StageBudgets.of(timeout).attribute,
    /** One `RateLimiter` call ([StageBudgets.rateLimit], RFC 0006 §8.3); then the rule's `strictFailure` decides. */
    val rateLimitTimeout: Duration = StageBudgets.of(timeout).rateLimit,
    /** Whether the token may come from the `authorization` query parameter; off, as tokens in URLs leak into logs. */
    val allowQueryToken: Boolean = false,
    /**
     * Whether the token may come from the `authorization` cookie (`cosec.pipeline.allow-cookie-token`); off, as a browser
     * sends a cookie on its own, on a cross-site request too: a host that turns this on defends against CSRF itself.
     */
    val allowCookieToken: Boolean = false,
    /** The header an API key comes in. */
    val apiKeyHeader: String = "X-API-Key",
    /** How much of the engine's trace decision events carry (`DecisionEvent.evaluationTrace`); `OFF` costs nothing. */
    val traceLevel: TraceLevel = TraceLevel.OFF,
) {
    init {
        require(timeout.isPositive()) { "The pipeline timeout must be positive, not $timeout." }
        require(authenticationTimeout.isPositive()) {
            "The authentication timeout must be positive, not $authenticationTimeout."
        }
        require(membershipTimeout.isPositive()) { "The membership timeout must be positive, not $membershipTimeout." }
        require(rateLimitTimeout.isPositive()) { "The rate limit timeout must be positive, not $rateLimitTimeout." }
        require(attributeTimeout.isPositive()) { "The attribute timeout must be positive, not $attributeTimeout." }
        require(apiKeyHeader.isNotBlank()) { "The API key header must not be blank." }
    }
}
