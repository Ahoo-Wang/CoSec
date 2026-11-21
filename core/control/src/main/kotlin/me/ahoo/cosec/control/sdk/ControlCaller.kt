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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.http.RequestSecurity

/**
 * Who a call through [ControlService] acts as (`CTL-65`). The control plane decides every call for this principal, by
 * the policies, and records it as this principal's: a caller states who acts, and is never trusted for what it may do.
 *
 * - [of]: the principal whose request the application is serving, from the [SecurityContext] CoSec made for that
 *   request (its gate or its pipeline). A context CoSec did not make is refused (as `PIP-29`): a principal is never
 *   taken from what an application assembled.
 * - [service]: the application's own principal, in the platform tenant, for work no request stands behind (a job, an
 *   onboarding at start-up). The application states the principal and its roles, and the control plane believes them,
 *   as it believes any code in its own process: that code holds the control store's write account anyway, so nothing
 *   is gained by doubting it. What it is believed about is who acts, never what that principal may do: the policies
 *   still decide each call for it, with the bindings the platform document gives it and the roles stated, and the
 *   record names it.
 */
@ExperimentalCoSecApi
class ControlCaller private constructor(
    /** The request CoSec permitted, for [of]. */
    @property:InternalCoSecApi
    val security: RequestSecurity?,
    /** The principal id, for [service]. */
    @property:InternalCoSecApi
    val principal: String?,
    @property:InternalCoSecApi
    val roles: Set<String>,
) {
    override fun toString(): String = security?.principal?.let { "ControlCaller(${it.id.value}@${it.tenant.value})" }
        ?: "ControlCaller(service $principal)"

    @ExperimentalCoSecApi
    companion object {
        /** The principal of the request [context] belongs to; [IllegalArgumentException] for a context CoSec did not make. */
        fun of(context: SecurityContext): ControlCaller {
            val security = requireNotNull(RequestSecurity.of(context)) {
                "This security context was not made by CoSec's gate or pipeline, so it names no principal the control " +
                    "plane may act as (PIP-29): pass the context of the request being served."
            }
            return ControlCaller(security, null, emptySet())
        }

        /** The application's own principal [principal] in the platform tenant, with [roles] in the default space. */
        fun service(principal: String, roles: Set<String> = emptySet()): ControlCaller {
            require(principal.isNotBlank()) { "A service principal has an id." }
            return ControlCaller(null, principal, roles.toSet())
        }
    }
}
