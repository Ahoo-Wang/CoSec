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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.webmvc

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.http.RequestSecurity

/**
 * The security context of the request the current thread serves, for blocking code on that thread: the servlet filter
 * binds it and clears it when the request completes.
 *
 * In a handler, take `SecurityContext` as a parameter instead ([SecurityContextArgumentResolver]): it is read from the
 * request. The holder is a thread-local, and a thread is not always its request's:
 * - an async dispatch, and a thread the application started, find it empty;
 * - a `suspend` handler goes on, after a suspension, on whatever thread resumed it. Unless Spring restores the holder
 *   there (Micrometer's context propagation with Reactor's automatic propagation, [SecurityContextThreadLocalAccessor]),
 *   it is unbound before such a handler runs ([SuspendingHandlerHolderInterceptor]), so [requireSecurityContext] fails at
 *   once, before or after any suspension, and [context] is null, which a caller must treat as "no context", never as
 *   "no restriction". One case is left: a `suspend` handler resumed on the thread of a request whose
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
     * The security context bound to the current thread; null when there is none, and for a context the pipeline did not
     * make (`PIP-27`). [requireSecurityContext] throws instead.
     */
    @JvmStatic
    fun securityContextOrNull(): SecurityContext? =
        SECURITY_CONTEXT_THREAD_LOCAL.get()?.takeIf { RequestSecurity.of(it) != null }

    /**
     * The security context bound to the current thread; [IllegalStateException] where there is none, saying where to
     * read it instead (UA-7).
     */
    @JvmStatic
    fun requireSecurityContext(): SecurityContext =
        securityContextOrNull() ?: throw IllegalStateException(Unbound.ON_THREAD)

    @InternalCoSecApi
    @JvmStatic
    fun remove() {
        SECURITY_CONTEXT_THREAD_LOCAL.remove()
    }
}
