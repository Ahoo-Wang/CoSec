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
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import reactor.core.publisher.Mono

/**
 * Policies see the request path within the application (the request URI without the context path), whatever the
 * `DispatcherServlet` mapping. Mapped under the `/api` prefix, `servletPath` is `/api` for every request.
 */
internal class ServletPathMappingTest {
    private val policy: Policy = CoSecJsonSerializer.readValue(
        """
        {
          "id": "api",
          "name": "api",
          "type": "global",
          "tenantId": "(platform)",
          "statements": [
            { "name": "deny-admin", "effect": "deny", "action": "/api/admin/**" },
            { "name": "allow-api", "action": "/api/**" }
          ]
        }
        """.trimIndent(),
        Policy::class.java,
    )

    private val filter = AuthorizationFilter(
        InjectSecurityContextParser,
        SimpleAuthorization(
            mockk<PolicyRepository> {
                every { getGlobalPolicy() } returns Mono.just(listOf(policy))
                every { getPolicies(any()) } returns Mono.just(listOf())
            },
            mockk<AppRolePermissionRepository> {
                every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
            },
        ),
        ServletRequestParser(ServletRemoteIpResolver),
    )

    private fun dispatch(contextPath: String, servletPath: String, pathInfo: String?): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", contextPath + servletPath + pathInfo.orEmpty()).apply {
            this.contextPath = contextPath
            this.servletPath = servletPath
            this.pathInfo = pathInfo
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        response.setCommitted(chain.request != null)
        return response
    }

    @Test
    fun denyAppliesUnderPrefixServletMapping() {
        val response = dispatch(contextPath = "", servletPath = "/api", pathInfo = "/admin/users")
        response.status.assert().isEqualTo(401)
    }

    @Test
    fun denyAppliesUnderPrefixServletMappingWithContextPath() {
        val response = dispatch(contextPath = "/shop", servletPath = "/api", pathInfo = "/admin/users")
        response.status.assert().isEqualTo(401)
    }

    @Test
    fun denyAppliesUnderDefaultServletMapping() {
        val response = dispatch(contextPath = "/shop", servletPath = "/api/admin/users", pathInfo = null)
        response.status.assert().isEqualTo(401)
    }

    @Test
    fun allowAppliesUnderPrefixServletMapping() {
        val response = dispatch(contextPath = "/shop", servletPath = "/api", pathInfo = "/orders")
        response.status.assert().isEqualTo(200)
        response.isCommitted.assert().isTrue()
    }

    @Test
    fun pathParametersDoNotHideDeny() {
        val response = dispatch(contextPath = "", servletPath = "/api", pathInfo = "/admin;jsessionid=1/users")
        response.status.assert().isEqualTo(401)
    }

    @Test
    fun uriOutsideContextPathIsRejected() {
        val request = MockHttpServletRequest("GET", "/other/api/orders").apply { contextPath = "/shop" }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        response.status.assert().isEqualTo(400)
        chain.request.assert().isNull()
    }
}
