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

import me.ahoo.cosec.authorization.Authorization
import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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
    companion object {
        private val log = LoggerFactory.getLogger(AbstractAuthorizationInterceptor::class.java)
    }

    protected fun authorize(
        request: HttpServletRequest,
        response: HttpServletResponse
    ): Boolean {
        val securityContext: SecurityContext
        try {
            securityContext = securityContextParser.parse(request)
        } catch (throwable: Throwable) {
            if (log.isInfoEnabled) {
                log.info(throwable.message, throwable)
            }
            response.status = HttpStatus.UNAUTHORIZED.value()
            return false
        }
        SecurityContextHolder.setContext(securityContext)
        request.setSecurityContext(securityContext)
        return authorization.authorize(requestParser.parse(request), securityContext)
            .map {
                if (!it.authorized) {
                    if (!securityContext.principal.authenticated()) {
                        response.status = HttpStatus.UNAUTHORIZED.value()
                    } else {
                        response.status = HttpStatus.FORBIDDEN.value()
                    }
                    return@map false
                }
                true
            }.block()!!
    }
}
