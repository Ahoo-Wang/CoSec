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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import org.slf4j.LoggerFactory
import java.io.IOException
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest

/**
 * Inject Security Context Filter .
 * 用于API网关授权检查后下游服务解析安全上下文，不需要进行Token校验。
 *
 * @author ahoo wang
 */
class InjectSecurityContextFilter(private val securityContextParser: SecurityContextParser<HttpServletRequest>) :
    Filter {
    companion object {
        private val log = LoggerFactory.getLogger(InjectSecurityContextFilter::class.java)
    }

    @Throws(IOException::class, ServletException::class)
    override fun doFilter(servletRequest: ServletRequest, servletResponse: ServletResponse, filterChain: FilterChain) {
        tryInjectSecurityContext(servletRequest)
        filterChain.doFilter(servletRequest, servletResponse)
    }

    private fun tryInjectSecurityContext(servletRequest: ServletRequest) {
        try {
            val httpServletRequest = servletRequest as HttpServletRequest
            val securityContext: SecurityContext = securityContextParser.parse(httpServletRequest)
            SecurityContextHolder.setContext(securityContext)
            httpServletRequest.setSecurityContext(securityContext)
        } catch (throwable: Throwable) {
            if (log.isInfoEnabled) {
                log.info(throwable.message, throwable)
            }
        }
    }
}
