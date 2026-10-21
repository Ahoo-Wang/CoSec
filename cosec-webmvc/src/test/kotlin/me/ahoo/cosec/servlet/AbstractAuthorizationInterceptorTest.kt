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

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.context.AUTHORIZATION_HEADER_KEY
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import reactor.kotlin.core.publisher.toMono

class AbstractAuthorizationInterceptorTest {
    /** A third-party subclass built with the pre-pipeline constructor. */
    private class LegacyInterceptor : AbstractAuthorizationInterceptor(
        ServletRequestParser(ServletRemoteIpResolver),
        InjectSecurityContextParser,
        { _, _ -> AuthorizeResult.ALLOW.toMono() },
    ) {
        fun check(request: HttpServletRequest, response: HttpServletResponse): Boolean = authorize(request, response)
    }

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.remove()
    }

    @Test
    fun legacyConstructorAuthorizes() {
        val servletRequest = mockk<HttpServletRequest> {
            every { requestURI } returns "/path"
            every { contextPath } returns ""
            every { method } returns "GET"
            every { remoteAddr } returns "127.0.0.1"
            every { getHeader(RequestIdCapable.REQUEST_ID_KEY) } returns "requestId"
            every { getHeader(AUTHORIZATION_HEADER_KEY) } returns null
            every { cookies } returns arrayOf()
            every { getHeader(HttpHeaders.ORIGIN) } returns null
            every { getHeader(HttpHeaders.REFERER) } returns null
            every { setAttribute(any(), any()) } just runs
        }
        val servletResponse = mockk<HttpServletResponse> {
            every { setHeader(RequestIdCapable.REQUEST_ID_KEY, "requestId") } just runs
        }
        LegacyInterceptor().check(servletRequest, servletResponse).assert().isTrue()
        SecurityContextHolder.context.assert().isNotNull()
    }
}
