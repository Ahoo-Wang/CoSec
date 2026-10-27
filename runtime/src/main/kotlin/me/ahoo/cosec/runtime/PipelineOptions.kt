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

import me.ahoo.cosec.engine.TraceLevel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the [Pipeline] bounds and reads a request. The defaults are RFC 0006 Q25's; the `cosec.*` properties that set
 * them arrive with the starter wiring at the switch (RFC 0008 §4.4).
 */
class PipelineOptions(
    /** The whole evaluation (`cosec.pipeline.timeout`): an upper bound, not the sum of the stages. Then 503. */
    val timeout: Duration = 5.seconds,
    /** One [me.ahoo.cosec.runtime.authn.Authenticator] call. Then 503. */
    val authenticationTimeout: Duration = 1.seconds,
    /** One `MembershipProvider` call (`cosec.authorization.membership.timeout`, RFC 0004). Then 503. */
    val membershipTimeout: Duration = 500.milliseconds,
    /**
     * One `AttributeProvider` call that sets no timeout of its own (`cosec.authorization.attributes.timeout`, RFC 0006
     * §5.4.2). Then its attributes are unavailable.
     */
    val attributeTimeout: Duration = 300.milliseconds,
    /** Whether the token may come from the `authorization` query parameter; off, as tokens in URLs leak into logs. */
    val allowQueryToken: Boolean = false,
    /** The header an API key comes in. */
    val apiKeyHeader: String = "X-API-Key",
    val traceLevel: TraceLevel = TraceLevel.OFF,
) {
    init {
        require(timeout.isPositive()) { "The pipeline timeout must be positive, not $timeout." }
        require(authenticationTimeout.isPositive()) {
            "The authentication timeout must be positive, not $authenticationTimeout."
        }
        require(membershipTimeout.isPositive()) { "The membership timeout must be positive, not $membershipTimeout." }
        require(attributeTimeout.isPositive()) { "The attribute timeout must be positive, not $attributeTimeout." }
        require(apiKeyHeader.isNotBlank()) { "The API key header must not be blank." }
    }
}
