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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext

/**
 * The security context of the request the current thread serves, for blocking code on that thread: the servlet filter
 * binds it and clears it when the request completes.
 *
 * In a handler, take `SecurityContext` as a parameter instead ([SecurityContextArgumentResolver]): it is read from the
 * request. The holder is a thread-local, and a thread is not always its request's:
 * - an async dispatch, and a thread the application started, find it empty;
 * - a `suspend` handler goes on, after a suspension, on whatever thread resumed it. Unless Spring restores the holder
 *   there (Micrometer's context propagation with Reactor's automatic propagation, [SecurityContextThreadLocalAccessor]),
 *   it is unbound before such a handler runs ([SuspendingHandlerHolderInterceptor]), so reading it fails at once,
 *   before or after any suspension. One case is left: a `suspend` handler resumed on the thread of a request whose
 *   handler is blocking finds that request's context there, so code that reads the holder only after such a
 *   resumption, never before a suspension, reads another request's principal.
 *
 * @see SecurityContext
 */
object SecurityContextHolder {
    /**
     * A plain [ThreadLocal]: an inheritable one would leak the request's context into any thread
     * created while serving it (e.g. lazily grown pool workers), where it is never cleared.
     */
    private val SECURITY_CONTEXT_THREAD_LOCAL: ThreadLocal<SecurityContext> = ThreadLocal()

    /**
     * Sets the security context for the current thread. Only the adapter binds it (round 2 L5): applications read.
     *
     * @param context The security context to set
     */
    @InternalCoSecApi
    @JvmStatic
    fun setContext(context: SecurityContext) {
        SECURITY_CONTEXT_THREAD_LOCAL.set(context)
    }

    /**
     * Gets the security context for the current thread.
     *
     * @return The current security context, or null if not set
     */
    @JvmStatic
    val context: SecurityContext?
        get() = SECURITY_CONTEXT_THREAD_LOCAL.get()

    /**
     * Gets the required security context, throwing if not set.
     *
     * @return The current security context
     * @throws IllegalStateException if context is not set
     */
    @JvmStatic
    val requiredContext: SecurityContext
        get() = requireNotNull(context) { UNBOUND }

    /** Why the holder is empty, and what to read instead. */
    internal const val UNBOUND =
        "No SecurityContext is bound to this thread: it is bound only on the request thread of blocking code. In a " +
            "handler, take SecurityContext as a handler parameter; elsewhere read it from the request " +
            "(ServletRequests.getSecurityContext). In a suspend handler the holder works only with context " +
            "propagation (io.micrometer:context-propagation and spring.reactor.context-propagation=auto); an async " +
            "dispatch and a thread the application started never have it."

    /**
     * Removes the security context from the current thread. Only the adapter does, when the request completes.
     */
    @InternalCoSecApi
    @JvmStatic
    fun remove() {
        SECURITY_CONTEXT_THREAD_LOCAL.remove()
    }
}
