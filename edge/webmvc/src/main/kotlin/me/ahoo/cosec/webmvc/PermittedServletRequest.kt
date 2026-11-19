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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.webmvc

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.runtime.http.Outcome
import java.security.Principal
import java.util.Collections
import java.util.Enumeration

/**
 * The request the chain sees of a request the pipeline permitted.
 *
 * - With [Outcome.forward] applied: the headers the gate sets on it, or removes, such as a `CoSec-Identity` a client
 *   sent to an enforcing host (RC1-18). Header names match ignoring case, as servlet containers match them.
 * - With CoSec's [principal] when CoSec authenticated the request (`PIP-28`): `getUserPrincipal()` is it, the object
 *   WebFlux and the gateway expose, and `getRemoteUser()` its id, replacing whatever the container or a filter in
 *   front had set, since CoSec decided the request. `isUserInRole` answers false and `getAuthType()` null then: CoSec
 *   does not answer container roles (its roles are per space; ask them of the `SecurityContext` with a check), and the
 *   wrapped request's would be another principal's roles. On a request CoSec treats as anonymous ([principal] null),
 *   all four are the wrapped request's, untouched: a principal there is another layer's, never CoSec's.
 */
internal class PermittedServletRequest private constructor(
    request: HttpServletRequest,
    private val forward: Map<String, String?>,
    private val principal: CoSecPrincipal?,
) : HttpServletRequestWrapper(request) {
    override fun getUserPrincipal(): Principal? = principal ?: super.getUserPrincipal()

    override fun getRemoteUser(): String? = if (principal != null) principal.id else super.getRemoteUser()

    override fun isUserInRole(role: String?): Boolean = principal == null && super.isUserInRole(role)

    override fun getAuthType(): String? = if (principal != null) null else super.getAuthType()

    /** Whether [name] is one of [forward]'s, and its value there: null when the gate removes it. */
    private fun forwarded(name: String): Pair<Boolean, String?> {
        for ((forwardedName, value) in forward) {
            if (forwardedName.equals(name, ignoreCase = true)) {
                return true to value
            }
        }
        return false to null
    }

    override fun getHeader(name: String): String? {
        val (named, value) = forwarded(name)
        return if (named) value else super.getHeader(name)
    }

    override fun getHeaders(name: String): Enumeration<String> {
        val (named, value) = forwarded(name)
        return if (named) Collections.enumeration(listOfNotNull(value)) else super.getHeaders(name)
    }

    override fun getHeaderNames(): Enumeration<String> {
        val names = Collections.list(super.getHeaderNames()).filterNot { forwarded(it).first }
        return Collections.enumeration(names + forward.filterValues { it != null }.keys)
    }

    override fun getIntHeader(name: String): Int {
        val (named, value) = forwarded(name)
        return if (named) value?.toInt() ?: -1 else super.getIntHeader(name)
    }

    override fun getDateHeader(name: String): Long {
        val (named, value) = forwarded(name)
        return when {
            !named -> super.getDateHeader(name)
            value == null -> -1
            else -> throw IllegalArgumentException("Header [$name] is not a date.")
        }
    }

    companion object {
        /**
         * [request] with [forward] applied and [principal], CoSec's when it authenticated the request, else null;
         * [request] itself when that changes nothing, so it is not wrapped.
         */
        fun of(
            request: HttpServletRequest,
            forward: Map<String, String?>,
            principal: CoSecPrincipal?,
        ): HttpServletRequest {
            val changesHeaders = forward.any { (name, value) -> value != null || request.getHeader(name) != null }
            if (principal == null && !changesHeaders) {
                return request
            }
            return PermittedServletRequest(request, forward, principal)
        }
    }
}
