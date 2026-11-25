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
import me.ahoo.cosec.control.change.ChangeOrigin
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.engine.SubjectAttributes
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity

/**
 * Who asks the control plane (RFC 0007 §10.1): a principal the host pipeline authenticated, with the memberships and
 * token attributes of that request. [security] is the permitted request itself; [PipelineControlAuthorizer] evaluates
 * the control-plane actions as it, and without it nothing is permitted.
 *
 * Every operator the control plane decides for is made by [of], with its request. The two made without one never reach
 * what reads the request's credential ([principalBindings], [breakGlass], whether it is a workload's): a recorded
 * approver, checked again at publication, is read for its tenant alone (`ApprovalRules`); the publisher an expiry names
 * settles a change without analysis (`Publishing.writtenBeforeExpiry`, whose moved heads are recorded unanalyzed). A new
 * caller that builds one without [security] keeps it away from those readings, whose defaults are a token's.
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
    /**
     * The address of the connection the request arrived on, when the adapter could read it beneath any forwarded-header
     * rewrite (`CTL-57`); null otherwise. Read where the control routes are served, not on every request.
     */
    val peer: String? = null,
    /** Whether the request came through the control plane's facade, in the host's own process (`CTL-65`). */
    val inProcess: Boolean = false,
    /**
     * Whether the request came through the control API's service profile, the service port or the facade (`CTL-81`). A
     * fact of the request, not of the identity: there the identity is only stated, by whatever workload the network
     * admitted, so no one is the trust root through it and every change it makes is analyzed. A forwarded identity on
     * the operators' port is stated too, behind the gateway that verified its credential, and stays eligible.
     */
    val serviceProfile: Boolean = false,
) {
    val tenant: TenantId get() = actor.tenant

    /** How this operator was identified, for the record (`CTL-57`); null without a request. */
    val origin: ChangeOrigin? get() = security?.let { ChangeOrigin.of(it, peer, inProcess) }

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
        fun of(
            security: RequestSecurity,
            peer: String? = null,
            inProcess: Boolean = false,
            serviceProfile: Boolean = false,
        ): Operator? {
            val principal = security.principal ?: return null
            return Operator(
                actor = Actor(principal.tenant, principal.id),
                memberships = security.memberships,
                attributes = principal.attributes,
                security = security,
                peer = peer,
                inProcess = inProcess,
                serviceProfile = serviceProfile,
            )
        }
    }
}
