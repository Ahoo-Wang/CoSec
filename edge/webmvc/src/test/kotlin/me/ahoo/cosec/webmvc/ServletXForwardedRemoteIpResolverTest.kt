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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.webmvc

import jakarta.servlet.http.HttpServletRequestWrapper
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.XForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ServletXForwardedRemoteIpResolverTest {

    @Test
    fun defaultMaxTrustedIndex() {
        val xForwardedRemoteIpResolver = ServletXForwardedRemoteIpResolver()
        // The default trusts exactly one hop: the rightmost entry.
        xForwardedRemoteIpResolver.maxTrustedIndex.assert().isEqualTo(1)
    }

    /** PIP-17, DR1-C-8: a container that reports no peer gives an address that could not be read, never empty. */
    @Test
    fun aRequestWithoutAPeerReadsAsUnknown() {
        val unreported = object : HttpServletRequestWrapper(MockHttpServletRequest("GET", "/orders")) {
            override fun getRemoteAddr(): String? = null
        }
        val empty = MockHttpServletRequest("GET", "/orders").apply { remoteAddr = "" }
        for (noPeer in listOf(unreported, empty)) {
            ServletRemoteIpResolver.resolve(noPeer).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
            ServletXForwardedRemoteIpResolver(0).resolve(noPeer).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
            ServletXForwardedRemoteIpResolver(1).resolve(noPeer).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
        }
        val peer = MockHttpServletRequest("GET", "/orders").apply { remoteAddr = "192.0.2.1" }
        ServletRemoteIpResolver.resolve(peer).assert().isEqualTo("192.0.2.1")
    }
}
