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

import io.micrometer.context.ContextRegistry
import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import me.ahoo.cosec.webmvc.ServletRequests.setSecurityContext
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest

/** Round 2 M4: a handler reads the context of its own request, from the request. */
class SecurityContextArgumentResolverTest {
    @Suppress("UNUSED_PARAMETER")
    fun handler(required: SecurityContext, optional: SecurityContext?, other: String) = Unit

    /** An application's own context type, which some other resolver of the application supplies. */
    interface TenantContext : SecurityContext

    @Suppress("UNUSED_PARAMETER")
    fun tenantHandler(tenant: TenantContext, any: Any) = Unit

    private val resolver = SecurityContextArgumentResolver()
    private fun parameter(index: Int) =
        MethodParameter.forExecutable(
            javaClass.getMethod(
                "handler",
                SecurityContext::class.java,
                SecurityContext::class.java,
                String::class.java
            ),
            index
        )

    @Test
    fun aContextParameterIsTheRequestsWhateverTheThreadHolds() {
        val context = PermittingPipeline.context()
        val other = PermittingPipeline.context()
        val request = MockHttpServletRequest().apply { setSecurityContext(context) }
        SecurityContextHolder.setContext(other)
        try {
            resolver.supportsParameter(parameter(0)).assert().isTrue()
            resolver.supportsParameter(parameter(2)).assert().isFalse()
            resolver.resolveArgument(parameter(0), null, ServletWebRequest(request), null).assert().isSameAs(context)
        } finally {
            SecurityContextHolder.remove()
        }
    }

    /** What the adapter binds is not of an application's subtype: such a parameter is left to its own resolver. */
    @Test
    fun aParameterOfASubtypeOrASupertypeIsNotThisResolvers() {
        val method = javaClass.getMethod("tenantHandler", TenantContext::class.java, Any::class.java)
        resolver.supportsParameter(MethodParameter.forExecutable(method, 0)).assert().isFalse()
        resolver.supportsParameter(MethodParameter.forExecutable(method, 1)).assert().isFalse()
        resolver.supportsParameter(parameter(1)).assert().isTrue()
    }

    @Test
    fun withoutAContextANullableParameterIsNullAndARequiredOneFails() {
        val request = ServletWebRequest(MockHttpServletRequest())
        resolver.resolveArgument(parameter(1), null, request, null).assert().isNull()
        // UA-7: the resolver says what the request's reader says.
        assertThrownBy<IllegalStateException> { resolver.resolveArgument(parameter(0), null, request, null) }
            .hasMessageEndingWith(Unbound.ON_REQUEST)
    }

    /** `PIP-27`: a context an application filter put on the request is none: never taken for a caller. */
    @Test
    fun aContextThePipelineDidNotMakeIsNone() {
        val servlet = MockHttpServletRequest()
        servlet.setAttribute(SecurityContext.KEY, mockk<SecurityContext>())
        servlet.securityContextOrNull().assert().isNull()
        val request = ServletWebRequest(servlet)
        resolver.resolveArgument(parameter(1), null, request, null).assert().isNull()
        assertThrownBy<IllegalStateException> { resolver.resolveArgument(parameter(0), null, request, null) }
        servlet.setAttribute(SecurityContext.KEY, "not a context")
        servlet.securityContextOrNull().assert().isNull()
    }

    /** Registered for Micrometer's context propagation, which captures and restores the holder with it. */
    @Test
    fun theHolderIsAThreadLocalOfContextPropagation() {
        val accessor = ContextRegistry.getInstance().threadLocalAccessors
            .single { it.key() == SecurityContextThreadLocalAccessor.KEY }
        val context = PermittingPipeline.context()
        try {
            @Suppress("UNCHECKED_CAST")
            (accessor as io.micrometer.context.ThreadLocalAccessor<SecurityContext>).setValue(context)
            SecurityContextHolder.securityContextOrNull().assert().isSameAs(context)
            accessor.getValue().assert().isSameAs(context)
            accessor.setValue()
            SecurityContextHolder.securityContextOrNull().assert().isNull()
        } finally {
            SecurityContextHolder.remove()
        }
    }
}
