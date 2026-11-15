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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.ForwardedIdentity
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal

/** [RequestSecurity] as the `SecurityContext` of `cosec-api`, with [scope] (RFC 0004 X6). */
internal class PipelineSecurityContext(val security: RequestSecurity) : SecurityContext {
    override val tenantId: String = security.scope.tenant.value

    override val principal: CoSecPrincipal = object : CoSecPrincipal {
        override val id: String = security.request.subject.id
        override val authenticated: Boolean = security.request.subject.authenticated
        override val attributes: Map<String, Any> = security.principal?.attributes.orEmpty()

        override fun toString(): String = "CoSecPrincipal(id=$id, authenticated=$authenticated)"
    }

    override val scope: SecurityScope = object : SecurityScope {
        override val appId: String = security.scope.app.value
        override val spaceId: String = security.scope.space.value
        override val roles: List<String> = security.scope.roles.map { it.value }

        override fun toString(): String = "SecurityScope(app=$appId, space=$spaceId, roles=$roles)"
    }

    override val forwardedIdentity: ForwardedIdentity? =
        security.identity?.let { ForwardedIdentity(it.requestId, it.credential.id) }

    override fun toString(): String = "PipelineSecurityContext(${security.requestId}, ${principal.id}@$tenantId)"
}
