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
import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.api.context.request.RequestIdCapable
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

internal class ServletRequestParserTest {

    @Test
    fun parse() {
        val servletRequestParser = ServletRequestParser(ServletRemoteIpResolver)
        val servletRequest = mockk<HttpServletRequest> {
            every { servletPath } returns "/path"
            every { method } returns "GET"
            every { remoteHost } returns "remoteHost"
            every { getHeader(HttpHeaders.ORIGIN) } returns "ORIGIN"
            every { getHeader(HttpHeaders.REFERER) } returns "REFERER"
            every { getHeader(RequestIdCapable.REQUEST_ID_KEY) } returns "requestId"
        }
        val request = servletRequestParser.parse(servletRequest)
        assertThat(request.path, equalTo("/path"))
        assertThat(request.method, equalTo("GET"))
    }
}
