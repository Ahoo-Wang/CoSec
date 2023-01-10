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

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import java.io.IOException
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Authorization Filter.
 *
 * @author ahoo wang
 * @see org.springframework.web.filter.OncePerRequestFilter
 */
class AuthorizationFilter(
    securityContextParser: SecurityContextParser<HttpServletRequest>,
    authorization: Authorization,
    requestParser: RequestParser<HttpServletRequest>,
) : AbstractAuthorizationInterceptor(requestParser, securityContextParser, authorization), Filter {
    @Throws(IOException::class, ServletException::class)
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpServletRequest = request as HttpServletRequest
        val httpServletResponse = response as HttpServletResponse
        if (authorize(httpServletRequest, httpServletResponse)) {
            chain.doFilter(request, response)
        }
    }
}
