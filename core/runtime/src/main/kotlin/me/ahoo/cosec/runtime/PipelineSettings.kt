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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.TraceLevel
import kotlin.time.Duration

/**
 * The pipeline's tunables, set in [PipelineBuilder.options] and read once, when the pipeline is built. Each starts at
 * its default; a property added later has a default too, so a host that sets none keeps compiling and keeps today's
 * behaviour.
 */
@ExperimentalCoSecApi
class PipelineSettings internal constructor() {
    /** The whole request, from the facts to the outcome (`cosec.pipeline.timeout`): past it, 503. */
    var timeout: Duration = DEFAULTS.timeout

    /** The authenticator's answer (`cosec.pipeline.authentication-timeout`): past it, 503. */
    var authenticationTimeout: Duration = DEFAULTS.authenticationTimeout

    /** The membership provider's answer (`cosec.authorization.membership.timeout`): past it, 503. */
    var membershipTimeout: Duration = DEFAULTS.membershipTimeout

    /** One attribute provider's answer, unless it sets its own (`cosec.authorization.attributes.timeout`). */
    var attributeTimeout: Duration = DEFAULTS.attributeTimeout

    /** The rate limiter's answer (`cosec.authorization.rate-limit.timeout`). */
    var rateLimitTimeout: Duration = DEFAULTS.rateLimitTimeout

    /** Whether a token is read from the `authorization` query parameter (`cosec.pipeline.allow-query-token`). */
    var allowQueryToken: Boolean = DEFAULTS.allowQueryToken

    /** Whether a token is read from the `authorization` cookie (`cosec.pipeline.allow-cookie-token`, `PIP-13`). */
    var allowCookieToken: Boolean = DEFAULTS.allowCookieToken

    /** The header an API key is read from (`cosec.pipeline.api-key-header`). */
    var apiKeyHeader: String = DEFAULTS.apiKeyHeader

    /** How much of the evaluation a decision event carries (`cosec.pipeline.trace-level`). */
    var traceLevel: TraceLevel = DEFAULTS.traceLevel

    internal fun toOptions(): PipelineOptions = PipelineOptions(
        timeout = timeout,
        authenticationTimeout = authenticationTimeout,
        membershipTimeout = membershipTimeout,
        attributeTimeout = attributeTimeout,
        rateLimitTimeout = rateLimitTimeout,
        allowQueryToken = allowQueryToken,
        allowCookieToken = allowCookieToken,
        apiKeyHeader = apiKeyHeader,
        traceLevel = traceLevel,
    )

    private companion object {
        val DEFAULTS = PipelineOptions()
    }
}
