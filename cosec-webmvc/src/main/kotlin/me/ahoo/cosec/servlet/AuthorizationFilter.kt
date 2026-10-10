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

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import java.io.IOException

/**
 * Servlet Filter for authorization in traditional web applications.
 *
 * This filter performs authorization checks for incoming HTTP requests
 * in servlet-based applications (Spring MVC).
 *
 * @param pipeline the security pipeline evaluating each request
 * @see AbstractAuthorizationInterceptor
 */
class AuthorizationFilter(
    pipeline: SecurityPipeline<HttpServletRequest>
) : AbstractAuthorizationInterceptor(pipeline),
    Filter {
    constructor(
        securityContextParser: SecurityContextParser,
        authorization: Authorization,
        requestParser: RequestParser<HttpServletRequest>
    ) : this(SecurityPipeline(requestParser, securityContextParser, authorization))

    @Throws(IOException::class, ServletException::class)
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain
    ) {
        // authorize() binds the SecurityContext to the current (pooled) thread via SecurityContextHolder.
        // It must be removed once the request completes, otherwise it bleeds into the next request served
        // by the same worker thread. Cleanup runs on every exit path, including early returns and errors.
        try {
            if (authorize(request as HttpServletRequest, response as HttpServletResponse)) {
                chain.doFilter(request, response)
            }
        } finally {
            SecurityContextHolder.remove()
        }
    }
}
