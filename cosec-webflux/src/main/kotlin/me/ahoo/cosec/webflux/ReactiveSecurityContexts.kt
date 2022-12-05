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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.api.context.SecurityContext
import reactor.core.publisher.Mono
import reactor.util.context.Context
import reactor.util.context.ContextView

object ReactiveSecurityContexts {

    fun ContextView.getSecurityContext(): SecurityContext {
        return get(SecurityContext.KEY)
    }

    fun Context.setSecurityContext(securityContext: SecurityContext): Context {
        return this.put(SecurityContext.KEY, securityContext)
    }

    /**
     * Write Security Context.
     */
    fun <T> Mono<T>.writeSecurityContext(securityContext: SecurityContext): Mono<T> {
        return contextWrite {
            it.setSecurityContext(securityContext)
        }
    }
}
