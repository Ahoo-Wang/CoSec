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

package me.ahoo.cosec.servlet

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.RequestIdCapable.Companion.REQUEST_ID_KEY
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import org.springframework.http.MediaType

/**
 * Abstract base class for authorization interceptors in servlet applications.
 *
 * Runs each request through a [SecurityPipeline] and translates the resulting
 * [me.ahoo.cosec.pipeline.SecurityDecision] into a servlet response. All authorization and error-mapping
 * logic lives in the pipeline, shared with the reactive integration.
 *
 * The resolved security context is bound to [SecurityContextHolder]; callers must clear it once the
 * request completes.
 *
 * @param pipeline the security pipeline evaluating each request
 * @see AuthorizationFilter
 */
abstract class AbstractAuthorizationInterceptor(
    val pipeline: SecurityPipeline<HttpServletRequest>
) {
    constructor(
        requestParser: RequestParser<HttpServletRequest>,
        securityContextParser: SecurityContextParser,
        authorization: Authorization
    ) : this(SecurityPipeline(requestParser, securityContextParser, authorization))

    /**
     * @return true when the request is authorized and may proceed; otherwise the response has been written
     */
    protected fun authorize(
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse
    ): Boolean {
        val decision = checkNotNull(pipeline.evaluate(servletRequest).block()) {
            "SecurityPipeline must always emit a decision."
        }
        val request = decision.request
        if (request == null) {
            servletResponse.status = decision.status.code
            return false
        }
        decision.securityContext?.let {
            SecurityContextHolder.setContext(it)
            servletRequest.setSecurityContext(it)
        }
        servletResponse.setHeader(REQUEST_ID_KEY, request.requestId)
        decision.responseHeaders.forEach { (name, value) -> servletResponse.setHeader(name, value) }
        if (!decision.allowed) {
            servletResponse.status = decision.status.code
            servletResponse.writeWithAuthorizeResult(decision.result)
            return false
        }
        return true
    }

    fun HttpServletResponse.writeWithAuthorizeResult(authorizeResult: AuthorizeResult) {
        contentType = MediaType.APPLICATION_JSON_VALUE
        outputStream.write(CoSecJsonSerializer.writeValueAsBytes(authorizeResult))
        outputStream.flush()
    }
}
