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
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import java.io.IOException

/**
 * Servlet filter for injecting security context without token verification.
 *
 * This filter is designed for downstream services behind an API gateway that has
 * already performed authorization checks. It parses the security context from
 * request headers without requiring token verification.
 *
 * @param requestParser Parser for converting servlet requests
 * @param securityContextParser Parser for extracting security context
 * @see SecurityContextParser.ensureParse
 */
class InjectSecurityContextFilter(
    private val requestParser: RequestParser<HttpServletRequest>,
    private val securityContextParser: SecurityContextParser
) :
    Filter {

    @Throws(IOException::class, ServletException::class)
    override fun doFilter(servletRequest: ServletRequest, servletResponse: ServletResponse, filterChain: FilterChain) {
        tryInjectSecurityContext(servletRequest)
        filterChain.doFilter(servletRequest, servletResponse)
    }

    private fun tryInjectSecurityContext(servletRequest: ServletRequest) {
        val httpServletRequest = servletRequest as HttpServletRequest
        val request = requestParser.parse(servletRequest)
        val securityContext: SecurityContext = securityContextParser.ensureParse(request)
        securityContext.setRequest(request)
        SecurityContextHolder.setContext(securityContext)
        httpServletRequest.setSecurityContext(securityContext)
    }
}
