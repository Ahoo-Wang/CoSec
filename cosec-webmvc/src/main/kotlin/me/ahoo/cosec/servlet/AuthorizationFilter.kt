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

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import org.springframework.http.HttpStatus
import java.io.IOException

/**
 * Servlet Filter for authorization in traditional web applications.
 *
 * This filter performs authorization checks for incoming HTTP requests
 * in servlet-based applications (Spring MVC).
 *
 * @param securityContextParser Parser for extracting security context
 * @param authorization The authorization service
 * @param requestParser Parser for converting servlet requests
 * @see AbstractAuthorizationInterceptor
 */
class AuthorizationFilter(
    securityContextParser: SecurityContextParser,
    authorization: Authorization,
    requestParser: RequestParser<HttpServletRequest>
) : AbstractAuthorizationInterceptor(requestParser, securityContextParser, authorization),
    Filter {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Throws(IOException::class, ServletException::class)
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain
    ) {
        val httpServletRequest = request as HttpServletRequest
        val httpServletResponse = response as HttpServletResponse
        // authorize() binds the SecurityContext to the current (pooled) thread via SecurityContextHolder.
        // It must be removed once the request completes, otherwise it bleeds into the next request served
        // by the same worker thread. Cleanup runs on every exit path, including early returns and errors.
        try {
            try {
                if (!authorize(httpServletRequest, httpServletResponse)) {
                    return
                }
            } catch (tooManyRequestsException: TooManyRequestsException) {
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                httpServletResponse.writeWithAuthorizeResult(AuthorizeResult.TOO_MANY_REQUESTS)
            } catch (regexTimeoutException: RegexTimeoutException) {
                // A regex condition exceeding its time budget (ReDoS guard) is an expected, fail-closed
                // authorization outcome -> deny, not a 5xx server error (which would invite client retries).
                httpServletResponse.status = HttpStatus.FORBIDDEN.value()
                httpServletResponse.writeWithAuthorizeResult(AuthorizeResult.IMPLICIT_DENY)
            } catch (cause: Exception) {
                log.error(cause) {
                    "Unexpected error during authorization of request [${httpServletRequest.servletPath}] [${httpServletRequest.method}]."
                }
                httpServletResponse.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
                httpServletResponse.writeWithAuthorizeResult(AuthorizeResult.IMPLICIT_DENY)
                return
            }

            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.remove()
        }
    }
}
