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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import reactor.core.publisher.Mono
import reactor.util.context.Context
import reactor.util.context.ContextView

object ReactiveSecurityContexts {

    /**
     * The security context the adapter wrote into this Reactor context; [IllegalStateException] where there is none,
     * saying what to do (`PIP-27`, UA-7). [securityContextOrNull] answers null instead.
     */
    @JvmStatic
    fun ContextView.requireSecurityContext(): SecurityContext =
        Unbound.required(getOrEmpty<Any>(SecurityContext.KEY).orElse(null), Unbound.Receiver.REACTOR_CONTEXT)

    /**
     * The security context the adapter wrote into this Reactor context; null when there is none, and for anything else
     * under [SecurityContext.KEY]: only a context CoSec's pipeline made is an authenticated caller's (`PIP-27`).
     */
    @JvmStatic
    fun ContextView.securityContextOrNull(): SecurityContext? =
        Unbound.orNull(getOrEmpty<Any>(SecurityContext.KEY).orElse(null), Unbound.Receiver.REACTOR_CONTEXT)

    /** Only the adapter binds it (round 2 L5): applications read it with [requireSecurityContext] or [securityContextOrNull]. */
    @InternalCoSecApi
    fun Context.setSecurityContext(securityContext: SecurityContext): Context {
        return this.put(SecurityContext.KEY, securityContext)
    }

    /** Writes the context into the Reactor context of the chain: the adapter's, never an application's (round 2 L5). */
    @InternalCoSecApi
    fun <T : Any> Mono<T>.writeSecurityContext(securityContext: SecurityContext): Mono<T> {
        return contextWrite {
            it.setSecurityContext(securityContext)
        }
    }
}
