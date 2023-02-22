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

package me.ahoo.cosec.webflux

import io.mockk.every
import io.mockk.mockk
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebExchange

class ReactiveRequestTest {
    @Test
    fun test() {
        val delegate = mockk<ServerWebExchange>() {
            every { request.headers.getFirst("key") } returns "value"
            every { request.headers.getFirst("not-exists") } returns null
        }
        val request = ReactiveRequest(
            delegate = delegate,
            path = "path",
            method = "method",
            remoteIp = "remoteIp",
            origin = "origin",
            referer = "referer"
        ).withAttributes(emptyMap())
        assertThat(
            request.toString(),
            `is`("ReactiveRequest(path='path', method='method', remoteIp='remoteIp', origin='origin', referer='referer')")
        )
        assertThat(
            request.getHeader("key"),
            `is`("value")
        )
        assertThat(
            request.getHeader("not-exists"),
            `is`("")
        )
    }
}