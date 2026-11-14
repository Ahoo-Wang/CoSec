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

import io.micrometer.context.ThreadLocalAccessor
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext

/**
 * [SecurityContextHolder] for Micrometer's context propagation (round 2 M4): where Spring captures and restores its own
 * thread-locals (a `suspend` handler under `spring.reactor.context-propagation=auto`, an executor wrapped by
 * `ContextExecutorService`), it captures and restores the holder too, so code that resumes on another thread reads
 * the context of its own request. Registered through `META-INF/services`, so only where the application has
 * `io.micrometer:context-propagation`.
 */
internal class SecurityContextThreadLocalAccessor : ThreadLocalAccessor<SecurityContext> {
    override fun key(): Any = KEY

    override fun getValue(): SecurityContext? = SecurityContextHolder.context

    override fun setValue(value: SecurityContext) = SecurityContextHolder.setContext(value)

    override fun setValue() = SecurityContextHolder.remove()

    companion object {
        const val KEY = "cosec.security-context"
    }
}
