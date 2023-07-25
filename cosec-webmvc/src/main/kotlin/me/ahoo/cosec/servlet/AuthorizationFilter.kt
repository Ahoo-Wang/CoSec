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
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import org.springframework.http.HttpStatus
import java.io.IOException

/**
 * Authorization Filter.
 *
 * @author ahoo wang
 * @see org.springframework.web.filter.OncePerRequestFilter
 */
class AuthorizationFilter(
    securityContextParser: SecurityContextParser<HttpServletRequest>,
    authorization: Authorization,
    requestParser: RequestParser<HttpServletRequest>
) : AbstractAuthorizationInterceptor(requestParser, securityContextParser, authorization), Filter {
    @Throws(IOException::class, ServletException::class)
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpServletRequest = request as HttpServletRequest
        val httpServletResponse = response as HttpServletResponse
        authorize(httpServletRequest, httpServletResponse)
            .doOnNext {
                if (it) {
                    chain.doFilter(request, response)
                }
            }
            .doOnError(TooManyRequestsException::class.java) {
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                httpServletResponse.writeWithAuthorizeResult(AuthorizeResult.TOO_MANY_REQUESTS)
            }
            .subscribe()
    }
}
