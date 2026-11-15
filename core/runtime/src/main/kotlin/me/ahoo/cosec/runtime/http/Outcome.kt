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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.spi.identity.Identity

/**
 * What an adapter does with a request (RFC 0006 §6.3): proceed to the handler, or write [status], [headers] and the
 * encoded [problem]. Only [StatusMapping] constructs one, so status codes and bodies have one definition, and adapters
 * never see a [Decision] or a [Cause] (`OneDecisionPathTest`).
 */
@ExperimentalCoSecApi
class Outcome internal constructor(
    /** The status to write; 200 when [proceed]. */
    val status: Int,
    /** Whether the request goes on to the handler. True only for a permit. */
    val proceed: Boolean,
    val requestId: String,
    /** The body to write with [ProblemEncoder]; null exactly when [proceed]. */
    val problem: Problem?,
    /** Response headers, `CoSec-Request-Id` among them, on every outcome. */
    val headers: Map<String, String>,
    /** Why: for audit and tests (RFC 0006 §9.2). Never written to the response. */
    internal val basis: Basis,
    /** Who may proceed, and in what scope: non-null exactly when [proceed]. Adapters expose it as a `SecurityContext`. */
    val security: RequestSecurity?,
    /**
     * Headers the adapter sets on the request it hands on when [proceed], to the handler or to a gateway's route; a null
     * value removes the header. A permit of the [Pipeline] always names `CoSec-Identity` here: the identity a gateway
     * forwards to the route, or null to strip whatever the client sent (RC1-18). A service behind a gateway names
     * nothing, and its handler keeps the identity the gateway sent.
     */
    val forward: Map<String, String?>,
) {
    override fun toString(): String = "Outcome(status=$status, proceed=$proceed, requestId=$requestId, basis=$basis)"
}

/** What an outcome is based on. */
internal sealed interface Basis {
    /** The request could not be interpreted without ambiguity (RFC 0006 §6.1; `SCP-4`). */
    data class InvalidRequest(val reason: String) : Basis

    data class Decided(val decision: Decision) : Basis

    /** A service behind a gateway proceeds with the identity the gateway verified; null for an anonymous request. */
    data class Asserted(val identity: Identity?) : Basis

    /** [permit] stopped at rate limit rule [ruleId], whose bucket is exhausted (RFC 0006 §8.3). */
    data class RateLimited(val permit: Decision.Permit, val ruleId: String) : Basis

    data class Unavailable(val reason: Unavailability) : Basis

    /** An exception nothing modelled: a defect of CoSec. */
    data class Error(val error: Throwable) : Basis
}

/** Why a request could not be decided: every one is 503 (RFC 0006 §6.1, Q1). */
internal sealed interface Unavailability {
    /** The engine, or a stage reporting in its terms (memberships, snapshot), found the decision indeterminate. */
    data class Indeterminate(val cause: Cause) : Unavailability

    /** The authenticator reported or threw a failure, or did not answer in time ([error] is then a timeout). */
    data class AuthenticatorUnavailable(val error: Throwable) : Unavailability

    /** The pipeline's own deadline passed (`cosec.pipeline.timeout`). */
    data object DeadlineExceeded : Unavailability
}
