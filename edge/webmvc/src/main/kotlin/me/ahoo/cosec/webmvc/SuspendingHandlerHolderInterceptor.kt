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

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.core.KotlinDetector
import org.springframework.util.ClassUtils
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import reactor.core.publisher.Hooks

/**
 * Unbinds [SecurityContextHolder] before a `suspend` handler runs, unless Spring will restore it on every resumption
 * (round 2 M4). Spring runs a `suspend` handler on `Dispatchers.Unconfined`: after a suspension it goes on on the thread
 * that resumed it, which may serve another request, whose context the holder would answer without a sound. Unbound,
 * the holder fails at once, in the first test a developer writes, naming the `SecurityContext` parameter.
 *
 * Spring restores thread-locals on resumption exactly when it invokes the handler with a `PropagationContextElement`:
 * when Reactor's automatic context propagation is on (`Hooks.isAutomaticContextPropagationEnabled`, which
 * `spring.reactor.context-propagation=auto` turns on) and Micrometer's context propagation, which restores the holder
 * through [SecurityContextThreadLocalAccessor], is on the classpath. Then the holder stays bound.
 *
 * The starter registers it last of the interceptors, so the others see the holder as the filter bound it.
 */
class SuspendingHandlerHolderInterceptor : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler is HandlerMethod && KotlinDetector.isSuspendingFunction(handler.method) && !restored()) {
            SecurityContextHolder.remove()
        }
        return true
    }

    companion object {
        private const val CONTEXT_PROPAGATION = "io.micrometer.context.ContextRegistry"
        private val PROPAGATION_PRESENT =
            ClassUtils.isPresent(CONTEXT_PROPAGATION, SuspendingHandlerHolderInterceptor::class.java.classLoader)

        /** Whether Spring restores the holder when a `suspend` handler resumes: what it keys on, as it is now. */
        @JvmStatic
        fun restored(): Boolean = PROPAGATION_PRESENT && Hooks.isAutomaticContextPropagationEnabled()
    }
}
