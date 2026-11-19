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

/**
 * Why a reader of this stack that must find a security context found none (UA-7), in one place so the readers cannot
 * drift: what is missing, why, what to do, and the stack's other reader.
 */
internal object Unbound {
    private fun message(where: String, instead: String) =
        "No SecurityContext $where: CoSec's filter did not handle this request (it is not registered, or this code " +
            "runs outside its chain). Register CoSec's WebFilter (the starter does), or $instead."

    /** [ReactiveSecurityContexts.requireSecurityContext]. */
    val IN_REACTOR_CONTEXT: String = message(
        "in this Reactor context",
        "where the chain's context is not at hand, read the exchange: ServerWebExchanges.securityContextOrNull(exchange)",
    )

    /** [ServerWebExchanges.requireSecurityContext]. */
    val ON_EXCHANGE: String = message(
        "on this exchange",
        "in a reactive chain, read the Reactor context: Mono.deferContextual { it.requireSecurityContext() } " +
            "(ReactiveSecurityContexts)",
    )
}
