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
package me.ahoo.cosec.context

import me.ahoo.cosec.api.context.SecurityContext

/**
 * Thread-local holder for security context.
 *
 * This object provides a convenient way to access the current
 * security context from anywhere in the application.
 *
 * @see SecurityContext
 * @see SimpleSecurityContext
 */
object SecurityContextHolder {
    private val SECURITY_CONTEXT_THREAD_LOCAL: ThreadLocal<SecurityContext> = InheritableThreadLocal()

    /**
     * Sets the security context for the current thread.
     *
     * @param context The security context to set
     */
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
        get() = requireNotNull(context) { "SecurityContext is null." }

    /**
     * Removes the security context from the current thread.
     */
    @JvmStatic
    fun remove() {
        SECURITY_CONTEXT_THREAD_LOCAL.remove()
    }
}
