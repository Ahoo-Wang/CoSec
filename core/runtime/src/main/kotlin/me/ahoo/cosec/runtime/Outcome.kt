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

import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.spi.identity.Identity

/**
 * What an adapter does with a request (RFC 0006 §6.3): proceed to the handler, or write [status], [headers] and the
 * encoded [problem]. Only [StatusMapping] constructs one, so status codes and bodies have one definition, and adapters
 * never see a [Decision] or a [Cause] (`OneDecisionPathTest`).
 */
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
     * Headers a gateway sets on the request it forwards, when [proceed]; a null value removes the header. A gateway
     * that forwards the verified identity always names `CoSec-Identity` here: the identity, or null to strip whatever
     * the client sent.
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

/**
 * The body of an outcome that does not proceed: an RFC 9457 problem (RFC 0006 §6.2). It holds what a client can act on
 * and nothing more: no policy, statement, binding, cause or exception (P16).
 */
class Problem internal constructor(
    val type: ProblemType,
    /** Equals the `CoSec-Request-Id` response header. */
    val requestId: String,
    /** The trace id captured at the bridge, when tracing is on; else absent from the body. */
    val traceId: String?,
) {
    val status: Int get() = type.status

    override fun toString(): String = "Problem(type=${type.slug}, requestId=$requestId, traceId=$traceId)"
}

/**
 * The problem vocabulary (RFC 0006 §6.1, Q27): one row per thing a client can do about it. Explicit and implicit
 * denies are both [UNAUTHORIZED] or [FORBIDDEN], so a response never tells a caller that a DENY exists. The `problem`
 * format descriptor in `cosec-core` lists the same rows, and `ProblemSchemaTest` keeps them equal.
 */
enum class ProblemType(val slug: String, val title: String, val status: Int) {
    INVALID_REQUEST("invalid-request", "Invalid Request", 400),
    UNAUTHORIZED("unauthorized", "Unauthorized", 401),
    TOKEN_INVALID("token-invalid", "Token Invalid", 401),
    TOKEN_EXPIRED("token-expired", "Token Expired", 401),
    TOKEN_REVOKED("token-revoked", "Token Revoked", 401),
    FORBIDDEN("forbidden", "Forbidden", 403),
    SCOPE_REJECTED("scope-rejected", "Scope Rejected", 403),
    TOO_MANY_REQUESTS("too-many-requests", "Too Many Requests", 429),
    UNAVAILABLE("unavailable", "Service Unavailable", 503),
    INTERNAL_ERROR("internal-error", "Internal Server Error", 500),
    ;

    /** The stable UPPER_SNAKE code, one-to-one with [slug]. */
    val code: String get() = name

    val uri: String get() = TYPE_PREFIX + slug

    companion object {
        /** Each URI resolves to the anchor of the same name in the troubleshooting guide (§6.2). */
        const val TYPE_PREFIX = "https://cosec.ahoo.me/problems/"
    }
}
