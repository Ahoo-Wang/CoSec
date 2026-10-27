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
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.http.CoSecParameters

/**
 * The one place status codes and problem bodies are defined (RFC 0006 §6.1, §6.3; `PIP-1`, `PIP-2`), and the only
 * constructor of [Outcome]. Every `when` is exhaustive over a sealed type, so a new decision or cause does not compile
 * until it is mapped.
 */
internal object StatusMapping {
    private const val OK = 200
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
    ): Outcome =
        when (decision) {
            is Decision.Permit -> Outcome(
                OK,
                true,
                requestId,
                null,
                requestIdHeader(requestId),
                Basis.Decided(decision),
                checkNotNull(security) { "A permit must carry who may proceed." },
            )
            is Decision.Deny -> problem(denied(decision, authn), requestId, traceId, Basis.Decided(decision))
            is Decision.Indeterminate ->
                unavailable(requestId, Unavailability.Indeterminate(decision.cause), traceId)
        }

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

    private fun problem(type: ProblemType, requestId: String, traceId: String?, basis: Basis): Outcome = Outcome(
        status = type.status,
        proceed = false,
        requestId = requestId,
        problem = Problem(type, requestId, traceId),
        headers = mapOf(CoSecParameters.REQUEST_ID to requestId, CONTENT_TYPE to ProblemEncoder.MEDIA_TYPE),
        basis = basis,
    )

    private fun requestIdHeader(requestId: String): Map<String, String> = mapOf(CoSecParameters.REQUEST_ID to requestId)
}
