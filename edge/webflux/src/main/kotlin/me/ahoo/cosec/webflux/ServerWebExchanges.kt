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
import org.springframework.web.server.ServerWebExchange

/**
 * Server Web Exchanges .
 *
 * @author ahoo wang
 */
object ServerWebExchanges {
    /** Only the adapter binds it (round 2 L5): applications read it with [requireSecurityContext] or [securityContextOrNull]. */
    @InternalCoSecApi
    @JvmStatic
    fun ServerWebExchange.setSecurityContext(securityContext: SecurityContext) {
        attributes[SecurityContext.KEY] = securityContext
    }

    /**
     * The security context the adapter bound to this exchange; null when there is none, and for anything else under
     * [SecurityContext.KEY], which an application filter or a library may have put there: only a context CoSec's
     * pipeline made is an authenticated caller's (`PIP-27`).
     */
    @JvmStatic
    fun ServerWebExchange.securityContextOrNull(): SecurityContext? =
        Unbound.orNull(attributes[SecurityContext.KEY], Unbound.Receiver.EXCHANGE)

    /**
     * The security context the adapter bound to this exchange; [IllegalStateException] where there is none, saying what
     * to do (UA-7). [securityContextOrNull] answers null instead.
     */
    @JvmStatic
    fun ServerWebExchange.requireSecurityContext(): SecurityContext =
        Unbound.required(attributes[SecurityContext.KEY], Unbound.Receiver.EXCHANGE)
}
