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

import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.identity.IdentityCodec
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.ratelimit.RateLimitResult

/**
 * The one place status codes and problem bodies are defined (RFC 0006 §6.1, §6.3; `PIP-1`, `PIP-2`), and the only
 * constructor of [Outcome]. Every `when` is exhaustive over a sealed type, so a new decision or cause does not compile
 * until it is mapped.
 */
internal object StatusMapping {
    private const val OK = 200
    private val NO_FORWARD: Map<String, String?> = emptyMap()
    private const val CONTENT_TYPE = "Content-Type"

    /** 400 `invalid-request`: an ambiguous path or `CoSec-*` parameter. The engine never sees the request. */
    fun invalidRequest(requestId: String, reason: String, traceId: String?): Outcome =
        problem(ProblemType.INVALID_REQUEST, requestId, traceId, Basis.InvalidRequest(reason))

    /**
     * A decision of the engine, or a scope rejection, for a principal authenticated as [authn]. A deny is 401 for an
     * anonymous principal and 403 for an authenticated one (RFC 0004 §4.6); when the credentials were rejected, the
     * client's remedy is new credentials, so the problem names why, whatever denied.
     */
    fun decided(
        requestId: String,
        decision: Decision,
        authn: AuthnResult,
        traceId: String?,
        security: RequestSecurity? = null,
        /** The tightest rate limit a permit spent, for its `RateLimit-*` headers (RFC 0006 §8.3). */
        rateLimit: RateLimitResult? = null,
    ): Outcome =
        when (decision) {
            is Decision.Permit -> Outcome(
                OK,
                true,
                requestId,
                null,
                if (rateLimit == null) {
                    requestIdHeader(
                        requestId
                    )
                } else {
                    requestIdHeader(requestId) + rateLimitHeaders(rateLimit)
                },
                Basis.Decided(decision),
                checkNotNull(security) { "A permit must carry who may proceed." },
                NO_FORWARD,
            )
            is Decision.Deny -> problem(denied(decision, authn), requestId, traceId, Basis.Decided(decision))
            is Decision.Indeterminate ->
                unavailable(requestId, Unavailability.Indeterminate(decision.cause), traceId)
        }

    /**
     * 429 `too-many-requests`: [permit] stopped at rule [ruleId], whose bucket is exhausted (RFC 0006 §8.3). With the
     * `RateLimit-*` headers of that rule and `Retry-After`.
     */
    fun tooManyRequests(
        requestId: String,
        permit: Decision.Permit,
        ruleId: String,
        result: RateLimitResult,
        traceId: String?,
    ): Outcome = problem(
        ProblemType.TOO_MANY_REQUESTS,
        requestId,
        traceId,
        Basis.RateLimited(permit, ruleId),
        rateLimitHeaders(result),
    )

    /** 503 `unavailable`: no decision could be made. Never with `Retry-After` (RFC 0006 Q1). */
    fun unavailable(requestId: String, reason: Unavailability, traceId: String?): Outcome =
        problem(ProblemType.UNAVAILABLE, requestId, traceId, Basis.Unavailable(reason))

    /** 500 `internal-error`: an exception nothing modelled, so a defect of CoSec. Its message never reaches the body. */
    fun error(requestId: String, error: Throwable, traceId: String?): Outcome =
        problem(ProblemType.INTERNAL_ERROR, requestId, traceId, Basis.Error(error))

    private fun denied(decision: Decision.Deny, authn: AuthnResult): ProblemType = when (authn) {
        is AuthnResult.Rejected -> when (authn.reason) {
            AuthnResult.RejectReason.TOKEN_EXPIRED -> ProblemType.TOKEN_EXPIRED
            AuthnResult.RejectReason.TOKEN_REVOKED -> ProblemType.TOKEN_REVOKED
            // An API key is a token to the client: the remedy is the same.
            AuthnResult.RejectReason.TOKEN_INVALID, AuthnResult.RejectReason.API_KEY_INVALID -> ProblemType.TOKEN_INVALID
        }

        is AuthnResult.Authenticated ->
            if (decision.by is StatementRef.ScopeRejected) ProblemType.SCOPE_REJECTED else ProblemType.FORBIDDEN

        // An anonymous principal has no memberships, so authenticating is all it can do, scope rejected or not.
        AuthnResult.Anonymous, is AuthnResult.Unavailable -> ProblemType.UNAUTHORIZED
    }

    /** [outcome], forwarding [identity] to the services behind the gateway, or stripping the header when null. */
    fun forwarding(outcome: Outcome, identity: String?): Outcome = with(outcome) {
        Outcome(
            status,
            proceed,
            requestId,
            problem,
            headers,
            basis,
            security,
            forward + (IdentityCodec.HEADER to identity)
        )
    }

    /** A service behind a gateway proceeds as [security], the identity the gateway forwarded or anonymous. */
    fun asserted(requestId: String, security: RequestSecurity, identity: Identity?): Outcome =
        Outcome(OK, true, requestId, null, requestIdHeader(requestId), Basis.Asserted(identity), security, NO_FORWARD)

    /**
     * A forwarded identity this service does not accept: 400 `invalid-request` when it cannot be read or is too large,
     * 401 `token-expired` when it or its credential is too old, 401 `token-invalid` for a version it does not read.
     */
    fun identityRejected(requestId: String, type: ProblemType, reason: String, traceId: String?): Outcome =
        problem(type, requestId, traceId, Basis.InvalidRequest(reason))

    private fun problem(
        type: ProblemType,
        requestId: String,
        traceId: String?,
        basis: Basis,
        extra: Map<String, String> = emptyMap(),
    ): Outcome = Outcome(
        status = type.status,
        proceed = false,
        requestId = requestId,
        problem = Problem(type, requestId, traceId),
        headers = mapOf(CoSecParameters.REQUEST_ID to requestId, CONTENT_TYPE to ProblemEncoder.MEDIA_TYPE) + extra,
        basis = basis,
        security = null,
        forward = NO_FORWARD,
    )

    /**
     * `RateLimit-Limit`, `-Remaining` and `-Reset` of [result], and `Retry-After` when it was exhausted: the headers
     * 7.x writes (`SecurityDecision.responseHeaders`), in seconds of at least 1.
     */
    private fun rateLimitHeaders(result: RateLimitResult): Map<String, String> {
        val reset = result.resetAfter.toSeconds().coerceAtLeast(1).toString()
        val headers = LinkedHashMap<String, String>(RATE_LIMIT_HEADERS)
        headers[RATE_LIMIT_LIMIT] = result.limit.toString()
        headers[RATE_LIMIT_REMAINING] = result.remaining.toString()
        headers[RATE_LIMIT_RESET] = reset
        if (!result.allowed) {
            headers[RETRY_AFTER] = reset
        }
        return headers
    }

    const val RATE_LIMIT_LIMIT = "RateLimit-Limit"
    const val RATE_LIMIT_REMAINING = "RateLimit-Remaining"
    const val RATE_LIMIT_RESET = "RateLimit-Reset"
    const val RETRY_AFTER = "Retry-After"
    private const val RATE_LIMIT_HEADERS = 4

    private fun requestIdHeader(requestId: String): Map<String, String> = mapOf(CoSecParameters.REQUEST_ID to requestId)
}
