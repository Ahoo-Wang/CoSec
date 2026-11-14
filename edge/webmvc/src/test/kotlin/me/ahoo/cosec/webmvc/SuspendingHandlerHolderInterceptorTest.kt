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

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.method.HandlerMethod
import reactor.core.publisher.Hooks

/** Round 2 M4: the holder is unbound before a suspend handler, unless Spring restores it on resumption. */
class SuspendingHandlerHolderInterceptorTest {
    class Handlers {
        suspend fun suspending(id: String): String = id

        fun blocking(id: String): String = id
    }

    private val interceptor = SuspendingHandlerHolderInterceptor()

    private fun handle(name: String): SecurityContext? {
        SecurityContextHolder.setContext(mockk())
        val method = Handlers::class.java.methods.first { it.name == name }
        interceptor.preHandle(MockHttpServletRequest(), MockHttpServletResponse(), HandlerMethod(Handlers(), method))
            .assert().isTrue()
        return SecurityContextHolder.context
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.remove()
        Hooks.disableAutomaticContextPropagation()
    }

    @Test
    fun aSuspendHandlerFindsTheHolderUnbound() {
        handle("suspending").assert().isNull()
    }

    @Test
    fun aBlockingHandlerKeepsTheHolder() {
        handle("blocking").assert().isNotNull()
        SecurityContextHolder.setContext(mockk())
        interceptor.preHandle(MockHttpServletRequest(), MockHttpServletResponse(), Any()).assert().isTrue()
        SecurityContextHolder.context.assert().isNotNull()
    }

    @Test
    fun underAutomaticContextPropagationASuspendHandlerKeepsTheHolder() {
        Hooks.enableAutomaticContextPropagation()
        SuspendingHandlerHolderInterceptor.restored().assert().isTrue()
        handle("suspending").assert().isNotNull()
    }
}
