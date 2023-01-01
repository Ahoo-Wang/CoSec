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

package me.ahoo.cosec.context.request

import io.mockk.mockk
import me.ahoo.cosec.api.context.request.Request
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*

import org.junit.jupiter.api.Test

class XForwardedRemoteIpResolverTest {
    private val defaultRemoteIpResolver: RemoteIpResolver<Request> = RemoteIpResolver { "default" }

    @Test
    fun resolveWhenMultiValues() {
        val xForwardedRemoteIpResolver = object : XForwardedRemoteIpResolver<Request>(defaultRemoteIpResolver) {
            override fun extractXForwardedHeaderValues(request: Request): List<String>? {
                return listOf("", "")
            }
        }
        assertThat(xForwardedRemoteIpResolver.resolve(mockk()), `is`("default"))
    }

    @Test
    fun resolveWhenNull() {
        val xForwardedRemoteIpResolver = object : XForwardedRemoteIpResolver<Request>(defaultRemoteIpResolver) {
            override fun extractXForwardedHeaderValues(request: Request): List<String>? {
                return null
            }
        }
        assertThat(xForwardedRemoteIpResolver.resolve(mockk()), `is`("default"))
    }

    @Test
    fun resolve() {
        val xForwardedRemoteIpResolver = object : XForwardedRemoteIpResolver<Request>(defaultRemoteIpResolver) {
            override fun extractXForwardedHeaderValues(request: Request): List<String>? {
                return listOf("ipAddress")
            }
        }
        assertThat(xForwardedRemoteIpResolver.resolve(mockk()), `is`("ipAddress"))
    }

    @Test
    fun resolveMulti() {
        val xForwardedRemoteIpResolver = object : XForwardedRemoteIpResolver<Request>(defaultRemoteIpResolver) {
            override fun extractXForwardedHeaderValues(request: Request): List<String>? {
                return listOf("ipAddress0, ipAddress1, ipAddress2")
            }
        }
        assertThat(xForwardedRemoteIpResolver.resolve(mockk()), `is`("ipAddress0"))
    }
}