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
import io.mockk.mockk
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.jwt.Jwts
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest

internal class InjectSecurityContextFilterTest {

    @Test
    fun doFilter() {
        val filter = InjectSecurityContextFilter(InjectSecurityContextParser)
        val request = mockk<HttpServletRequest>() {
            every { getHeader(Jwts.AUTHORIZATION_KEY) } returns null
            every { setSecurityContext(any()) } returns Unit
        }
        val filterChain = mockk<FilterChain>() {
            every { doFilter(request, any()) } returns Unit
        }
        filter.doFilter(request, mockk(), filterChain)
        assertThat(SecurityContextHolder.requiredContext, equalTo(SimpleSecurityContext.ANONYMOUS))
    }

    @Test
    fun doFilterThrow() {
        val filter = InjectSecurityContextFilter(InjectSecurityContextParser)
        val request = mockk<HttpServletRequest>() {
            every { getHeader(Jwts.AUTHORIZATION_KEY) } returns null
            every { setSecurityContext(any()) } returns Unit
        }
        val filterChain = mockk<FilterChain>() {
            every { doFilter(request, any()) } returns Unit
        }
        filter.doFilter(request, mockk(), filterChain)
    }
}
