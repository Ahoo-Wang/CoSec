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

package me.ahoo.cosec.pipeline

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.VerifyContext

/**
 * Transport-neutral outcome of a request passing through [SecurityPipeline].
 *
 * @param code the HTTP status code a transport should respond with when the request is not [ALLOWED]
 */
enum class SecurityStatus(val code: Int) {
    ALLOWED(200),
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    TOO_MANY_REQUESTS(429),
    ERROR(500)
}

/**
 * The result of running a request through [SecurityPipeline].
 *
 * @param status what the transport should do with the request
 * @param result the authorization result, written as the response body when the request is rejected
 * @param request the parsed request; null only when [status] is [SecurityStatus.BAD_REQUEST]
 * @param securityContext the resolved security context; null when the request or context could not be parsed
 * @param verifyContext what produced the authorization result, when reported by the authorization
 */
data class SecurityDecision(
    val status: SecurityStatus,
    val result: AuthorizeResult,
    val request: Request? = null,
    val securityContext: SecurityContext? = null,
    val verifyContext: VerifyContext? = null
) {
    val allowed: Boolean
        get() = status == SecurityStatus.ALLOWED

    companion object {
        val BAD_REQUEST = SecurityDecision(SecurityStatus.BAD_REQUEST, AuthorizeResult.IMPLICIT_DENY)
    }
}
