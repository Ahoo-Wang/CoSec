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
 * Security Context Holder.
 *
 * @author ahoo wang
 */
object SecurityContextHolder {
    private val SECURITY_CONTEXT_THREAD_LOCAL: ThreadLocal<SecurityContext> = InheritableThreadLocal()

    @JvmStatic
    fun setContext(context: SecurityContext) {
        SECURITY_CONTEXT_THREAD_LOCAL.set(context)
    }

    @JvmStatic
    val context: SecurityContext?
        get() = SECURITY_CONTEXT_THREAD_LOCAL.get()

    @JvmStatic
    val requiredContext: SecurityContext
        get() = requireNotNull(context) { "SecurityContext is null." }

    @JvmStatic
    fun remove() {
        SECURITY_CONTEXT_THREAD_LOCAL.remove()
    }
}
