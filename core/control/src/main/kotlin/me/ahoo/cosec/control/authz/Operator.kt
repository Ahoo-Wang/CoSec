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

package me.ahoo.cosec.control.authz

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.engine.SubjectAttributes
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity

/**
 * Who asks the control plane (RFC 0007 §10.1): a principal the host pipeline authenticated, with the memberships and
 * token attributes of that request. [security] is the permitted request itself; [PipelineControlAuthorizer] evaluates
 * the control-plane actions as it, and without it nothing is permitted.
 */
@InternalCoSecApi
class Operator(
    val actor: Actor,
    val memberships: Memberships,
    /**
     * The token's `attributes` claim, of every JSON type, as the engine holds them: `subject.attributes.<name>` reads
     * each through [SubjectAttributes.string], and so does the grant analysis (RC1-2).
     */
    val attributes: Map<String, Any> = emptyMap(),
    val security: RequestSecurity? = null,
) {
    val tenant: TenantId get() = actor.tenant

    val platform: Boolean get() = actor.tenant == TenantId.PLATFORM

    /**
     * Whether the operator's principal bindings reach it in this request: not with a credential that has a role cap
     * (RFC 0009 §4.2). Without a request, which permits nothing anyway, as a token would.
     */
    val principalBindings: Boolean get() = security?.principal?.principalBindings ?: true

    /** Whether break-glass could reach the operator in this request: only with CoSec's own token. */
    val breakGlass: Boolean get() = security?.principal?.breakGlass ?: true

    override fun toString(): String = "Operator(${actor.principal.value}@${actor.tenant.value})"

    @InternalCoSecApi
    companion object {
        /** The operator of a request the host pipeline permitted; null for an anonymous one. */
        fun of(security: RequestSecurity): Operator? {
            val principal = security.principal ?: return null
            return Operator(
                actor = Actor(principal.tenant, principal.id),
                memberships = security.memberships,
                attributes = principal.attributes,
                security = security,
            )
        }
    }
}
