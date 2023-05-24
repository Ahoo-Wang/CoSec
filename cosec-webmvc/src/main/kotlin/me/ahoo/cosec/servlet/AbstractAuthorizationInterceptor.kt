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
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import me.ahoo.cosec.token.TokenVerificationException
import me.ahoo.cosec.token.asAuthorizeResult
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Abstract Authorization Interceptor .
 *
 * @author ahoo wang
 */
abstract class AbstractAuthorizationInterceptor(
    private val requestParser: RequestParser<HttpServletRequest>,
    private val securityContextParser: SecurityContextParser<HttpServletRequest>,
    private val authorization: Authorization
) {

    protected fun authorize(
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse
    ): Boolean {
        var tokenVerificationException: TokenVerificationException? = null
        val securityContext = try {
            securityContextParser.parse(servletRequest)
        } catch (verificationException: TokenVerificationException) {
            tokenVerificationException = verificationException
            SimpleSecurityContext.anonymous()
        }

        SecurityContextHolder.setContext(securityContext)
        servletRequest.setSecurityContext(securityContext)
        val request = requestParser.parse(servletRequest)
        securityContext.setRequest(request)
        return authorization.authorize(request, securityContext)
            .map {
                if (!it.authorized) {
                    if (!securityContext.principal.authenticated()) {
                        servletResponse.status = HttpStatus.UNAUTHORIZED.value()
                    } else {
                        servletResponse.status = HttpStatus.FORBIDDEN.value()
                    }

                    servletResponse.writeWithAuthorizeResult(
                        tokenVerificationException?.asAuthorizeResult() ?: it,
                    )
                    return@map false
                }
                true
            }.block()!!
    }

    fun HttpServletResponse.writeWithAuthorizeResult(authorizeResult: AuthorizeResult) {
        contentType = MediaType.APPLICATION_JSON_VALUE
        outputStream.write(CoSecJsonSerializer.writeValueAsBytes(authorizeResult))
        outputStream.flush()
    }
}
