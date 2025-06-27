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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.net.URI

class CoSecServletRequestTest {
    @Test
    fun test() {
        val delegate = mockk<HttpServletRequest> {
            every { getHeader("key") } returns "value"
            every { getHeader("not-exists") } returns null
            every { getParameter("key") } returns "value"
        }
        val request = CoSecServletRequest(
            delegate = delegate,
            path = "path",
            method = "method",
            remoteIp = "remoteIp",
            origin = URI.create("http://origin"),
            referer = URI.create("http://referer"),
            requestId = "requestId"
        ).withAttributes(emptyMap())
        request.toString().assert().isEqualTo(
            "CoSecServletRequest(path='path', method='method', remoteIp='remoteIp', origin='http://origin', referer='http://referer')"
        )
        request.getHeader("key").assert().isEqualTo("value")
        request.getQuery("key").assert().isEqualTo("value")
        request.getHeader("not-exists").assert().isEqualTo("")
    }
}
