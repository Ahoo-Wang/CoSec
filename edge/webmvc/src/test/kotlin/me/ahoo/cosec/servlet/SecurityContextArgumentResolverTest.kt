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

package me.ahoo.cosec.servlet

import io.micrometer.context.ContextRegistry
import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
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
        val context = mockk<SecurityContext>()
        val other = mockk<SecurityContext>()
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

    @Test
    fun withoutAContextANullableParameterIsNullAndARequiredOneFails() {
        val request = ServletWebRequest(MockHttpServletRequest())
        resolver.resolveArgument(parameter(1), null, request, null).assert().isNull()
        assertThrownBy<IllegalStateException> { resolver.resolveArgument(parameter(0), null, request, null) }
    }

    /** Registered for Micrometer's context propagation, which captures and restores the holder with it. */
    @Test
    fun theHolderIsAThreadLocalOfContextPropagation() {
        val accessor = ContextRegistry.getInstance().threadLocalAccessors
            .single { it.key() == SecurityContextThreadLocalAccessor.KEY }
        val context = mockk<SecurityContext>()
        try {
            @Suppress("UNCHECKED_CAST")
            (accessor as io.micrometer.context.ThreadLocalAccessor<SecurityContext>).setValue(context)
            SecurityContextHolder.context.assert().isSameAs(context)
            accessor.getValue().assert().isSameAs(context)
            accessor.setValue()
            SecurityContextHolder.context.assert().isNull()
        } finally {
            SecurityContextHolder.remove()
        }
    }
}
