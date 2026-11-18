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
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal

/** A control-plane decision. Only [PERMIT] lets a step through; [INDETERMINATE] is a 503, [DENY] a 403 (RFC 0007 §7.2). */
@InternalCoSecApi
enum class ControlDecision {
    PERMIT,
    DENY,
    INDETERMINATE,
}

/**
 * Decides one control-plane action (RFC 0007 §7.2): [action] is a `cosec:verb` of [ControlActions], [resource] one of
 * [ControlResources]. An implementation evaluates on documents it has confirmed current, and fails closed.
 */
@InternalCoSecApi
fun interface ControlAuthorizer {
    suspend fun decide(operator: Operator, action: Action, resource: Resource): ControlDecision

    /**
     * [action] on [resource] for [principal] with [memberships], a principal recorded earlier (the approver of a
     * change, RFC 0007 §7.5), asked in [operator]'s request, on the current documents. Indeterminate unless an
     * implementation can check a recorded principal: an approval that cannot be checked again does not count.
     */
    suspend fun decideAs(
        operator: Operator,
        principal: AuthenticatedPrincipal,
        memberships: Memberships,
        action: Action,
        resource: Resource,
    ): ControlDecision = ControlDecision.INDETERMINATE

    /**
     * Runs [block], one change's authorization (#1218), with an authorizer whose decisions may share what one
     * confirmation of the documents made current, as long as those documents are the same at the end. An implementation
     * that confirms before every decision answers with [this] and [block] as it is; [PipelineControlAuthorizer]
     * confirms once, evaluates every decision on that view, confirms again at the end, and runs [block] again when a
     * confirmed document moved meanwhile. A decision is never taken from a view older than the start of [block], and
     * nothing [block] returns rests on documents that changed during it.
     */
    suspend fun <T> batch(block: suspend (ControlAuthorizer) -> T): T = block(this)
}
