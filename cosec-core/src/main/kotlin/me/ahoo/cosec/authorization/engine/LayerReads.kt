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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.engine.AbsentReason
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.snapshot.SnapshotKey
import reactor.core.Exceptions

/**
 * What a request's layers read from the request and its principal, read once, in 7.0's order and under its
 * conditions (RFC 0002 X1): the principal's policies, then its roles, the space and the app, each only when the one
 * before leaves something to read. A read that throws fails its layer, when the evaluation reaches it.
 *
 * The one place these reads happen, for both [PolicySetLoader] and [SnapshotPolicySetLoader]. Read before any policy,
 * because [appDocument] decides which app document a snapshot needs.
 */
internal class LayerReads private constructor(
    /** The principal layer when the reads already decide it; otherwise null and [policyIds] is set. */
    val principalLayer: LayerInput?,
    val policyIds: Set<PolicyId>,
    /** The role layer when the reads already decide it; otherwise null and [appId] is set. */
    val roleLayer: LayerInput?,
    /** The request's app, when the role layer read it. */
    val appId: AppId?,
    val spaceId: SpaceId,
    val scopedRoles: Set<RoleId>,
) {
    /**
     * The app document a snapshot needs, or null when it needs none. An app id that cannot name a document needs
     * none: 7.0's repository gives it no app permission.
     */
    val appDocument: AppId? get() = appId?.takeIf { roleLayer == null && SnapshotKey.App.isValidAppId(it) }

    companion object {
        private val NO_PRINCIPAL_POLICIES: LayerInput = LayerInput.Absent(LayerKind.PRINCIPAL, AbsentReason.NO_POLICIES)
        private val NO_ROLES: LayerInput = LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_ROLES)
        private val NO_ROLE_IN_SPACE: LayerInput = LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_ROLE_IN_SPACE)

        /** Reads what the layers of [facts]'s request need. */
        @Suppress("TooGenericExceptionCaught")
        fun of(facts: LegacyFacts): LayerReads {
            var principalLayer: LayerInput? = null
            var policyIds: Set<PolicyId> = emptySet()
            try {
                policyIds = facts.base.principal.policies
                if (policyIds.isEmpty()) {
                    principalLayer = NO_PRINCIPAL_POLICIES
                }
            } catch (error: Throwable) {
                principalLayer = unavailable(LayerKind.PRINCIPAL, error)
            }
            try {
                val roles = facts.base.principal.roles
                if (roles.isEmpty()) {
                    return LayerReads(principalLayer, policyIds, NO_ROLES, null, "", emptySet())
                }
                val spaceId = facts.request.spaceId
                val scopedRoles = RoleScope.rolesIn(spaceId, roles)
                if (scopedRoles.isEmpty()) {
                    return LayerReads(principalLayer, policyIds, NO_ROLE_IN_SPACE, null, spaceId, scopedRoles)
                }
                val appId = facts.request.appId
                return LayerReads(principalLayer, policyIds, null, appId, spaceId, scopedRoles)
            } catch (error: Throwable) {
                val roleLayer = unavailable(LayerKind.ROLE, error)
                return LayerReads(principalLayer, policyIds, roleLayer, null, "", emptySet())
            }
        }

        private fun unavailable(kind: LayerKind, error: Throwable): LayerInput {
            Exceptions.throwIfJvmFatal(error)
            return LayerInput.Unavailable(kind, error)
        }
    }
}
