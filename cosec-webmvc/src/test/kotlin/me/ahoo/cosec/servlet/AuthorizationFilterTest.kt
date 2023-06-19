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
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import me.ahoo.cosec.token.TokenVerificationException
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import reactor.kotlin.core.publisher.toMono

internal class AuthorizationFilterTest {

    @Test
    fun doFilter() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val filter = AuthorizationFilter(
            InjectSecurityContextParser,
            authorization,
            ServletRequestParser(ServletRemoteIpResolver),
        )
        val servletRequest = mockk<HttpServletRequest> {
            every { servletPath } returns "/path"
            every { method } returns "GET"
            every { remoteHost } returns "remoteHost"
            every { getHeader(Jwts.AUTHORIZATION_KEY) } returns null
            every { getHeader(HttpHeaders.ORIGIN) } returns null
            every { getHeader(HttpHeaders.REFERER) } returns null
            every { setSecurityContext(any()) } returns Unit
        }
        val filterChain = mockk<FilterChain> {
            every { doFilter(servletRequest, any()) } returns Unit
        }
        filter.doFilter(servletRequest, mockk<HttpServletResponse>(), filterChain)
        assertThat(SecurityContextHolder.requiredContext.principal, equalTo(SimpleTenantPrincipal.ANONYMOUS))
    }

    @Test
    fun doFilterDeny() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.EXPLICIT_DENY.toMono()
        }
        val filter = AuthorizationFilter(
            InjectSecurityContextParser,
            authorization,
            ServletRequestParser(ServletRemoteIpResolver),
        )
        val servletRequest = mockk<HttpServletRequest> {
            every { servletPath } returns "/path"
            every { method } returns "GET"
            every { remoteHost } returns "remoteHost"
            every { getHeader(Jwts.AUTHORIZATION_KEY) } returns null
            every { getHeader(HttpHeaders.ORIGIN) } returns "ORIGIN"
            every { getHeader(HttpHeaders.REFERER) } returns "REFERER"
            every { setSecurityContext(any()) } returns Unit
        }
        val servletResponse = mockk<HttpServletResponse> {
            every { contentType = MediaType.APPLICATION_JSON_VALUE } just runs
            every { status = HttpStatus.UNAUTHORIZED.value() } returns Unit
            every { outputStream.write(any() as ByteArray) } returns Unit
            every { outputStream.flush() } returns Unit
        }
        val filterChain = mockk<FilterChain> {
            every { doFilter(servletRequest, any()) } returns Unit
        }
        filter.doFilter(servletRequest, servletResponse, filterChain)
        assertThat(SecurityContextHolder.requiredContext.principal, equalTo(SimpleTenantPrincipal.ANONYMOUS))
    }

    @Test
    fun doFilterWhenTokenInvalid() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.EXPLICIT_DENY.toMono()
        }
        val securityContextParser = mockk<SecurityContextParser<HttpServletRequest>> {
            every { parse(any()) } throws TokenVerificationException()
        }
        val filter = AuthorizationFilter(
            securityContextParser,
            authorization,
            ServletRequestParser(ServletRemoteIpResolver),
        )
        val servletRequest = mockk<HttpServletRequest> {
            every { servletPath } returns "/path"
            every { method } returns "GET"
            every { remoteHost } returns "remoteHost"
            every { getHeader(HttpHeaders.ORIGIN) } returns "ORIGIN"
            every { getHeader(HttpHeaders.REFERER) } returns "REFERER"
            every { setSecurityContext(any()) } returns Unit
        }
        val servletResponse = mockk<HttpServletResponse> {
            every { contentType = MediaType.APPLICATION_JSON_VALUE } just runs
            every { status = HttpStatus.UNAUTHORIZED.value() } returns Unit
            every { outputStream.write(any() as ByteArray) } returns Unit
            every { outputStream.flush() } returns Unit
        }
        val filterChain = mockk<FilterChain>()
        filter.doFilter(servletRequest, servletResponse, filterChain)

        verify {
            securityContextParser.parse(any())
            servletResponse.contentType = MediaType.APPLICATION_JSON_VALUE
            servletResponse.status = HttpStatus.UNAUTHORIZED.value()
            servletResponse.outputStream.write(any() as ByteArray)
            servletResponse.outputStream.flush()
        }
    }

    @Test
    fun doFilterWhenTooManyRequests() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } throws TooManyRequestsException()
        }
        val filter = AuthorizationFilter(
            InjectSecurityContextParser,
            authorization,
            ServletRequestParser(ServletRemoteIpResolver),
        )
        val servletRequest = mockk<HttpServletRequest> {
            every { servletPath } returns "/path"
            every { method } returns "GET"
            every { remoteHost } returns "remoteHost"
            every { getHeader(Jwts.AUTHORIZATION_KEY) } returns null
            every { getHeader(HttpHeaders.ORIGIN) } returns null
            every { getHeader(HttpHeaders.REFERER) } returns null
            every { setSecurityContext(any()) } returns Unit
        }
        val servletResponse = mockk<HttpServletResponse> {
            every { contentType = MediaType.APPLICATION_JSON_VALUE } just runs
            every { status = HttpStatus.TOO_MANY_REQUESTS.value() } returns Unit
            every { outputStream.write(any() as ByteArray) } returns Unit
            every { outputStream.flush() } returns Unit
        }
        val filterChain = mockk<FilterChain> {
            every { doFilter(servletRequest, any()) } returns Unit
        }
        filter.doFilter(servletRequest, servletResponse, filterChain)

        verify {
            servletResponse.contentType = MediaType.APPLICATION_JSON_VALUE
            servletResponse.status = HttpStatus.TOO_MANY_REQUESTS.value()
            servletResponse.outputStream.write(any() as ByteArray)
            servletResponse.outputStream.flush()
        }
    }
}
