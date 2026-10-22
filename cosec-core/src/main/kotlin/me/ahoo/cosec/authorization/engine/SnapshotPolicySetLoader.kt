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
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.engine.AbsentReason
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.SnapshotKey
import reactor.core.Exceptions

/**
 * What a request's layers read from the request and its principal, read once, in 7.0's order and under its
 * conditions (RFC 0002 X1): the principal's policies, then its roles, the space and the app, each only when the one
 * before leaves something to read. A read that throws fails its layer, when the evaluation reaches it.
 *
 * Read before the snapshot is taken, because [appId] decides which app document the request needs.
 */
internal class SnapshotReads private constructor(
    /** The principal layer when the reads already decide it; otherwise null and [policyIds] is set. */
    val principalLayer: LayerInput?,
    val policyIds: Set<PolicyId>,
    /** The role layer when the reads already decide it; otherwise null and [appId] is set. */
    val roleLayer: LayerInput?,
    /** The app document the request needs, or null when it needs none. */
    val appId: AppId?,
    val spaceId: SpaceId,
    val scopedRoles: Set<RoleId>,
) {
    companion object {
        /** Reads what the layers of [facts]'s request need. */
        @Suppress("TooGenericExceptionCaught")
        fun of(facts: LegacyFacts): SnapshotReads {
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
                    return SnapshotReads(principalLayer, policyIds, NO_ROLES, null, "", emptySet())
                }
                val spaceId = facts.request.spaceId
                val scopedRoles = RoleScope.rolesIn(spaceId, roles)
                if (scopedRoles.isEmpty()) {
                    return SnapshotReads(principalLayer, policyIds, NO_ROLE_IN_SPACE, null, spaceId, scopedRoles)
                }
                val appId = facts.request.appId
                // 7.0's repository gives an app id that cannot own grants no app permission.
                if (!SnapshotKey.App.isValidAppId(appId)) {
                    return SnapshotReads(principalLayer, policyIds, NO_APP_PERMISSION, null, spaceId, scopedRoles)
                }
                return SnapshotReads(principalLayer, policyIds, null, appId, spaceId, scopedRoles)
            } catch (error: Throwable) {
                val roleLayer = unavailable(LayerKind.ROLE, error)
                return SnapshotReads(principalLayer, policyIds, roleLayer, null, "", emptySet())
            }
        }
    }
}

/**
 * Builds a request's [PolicySet] from a compiled snapshot (RFC 0003 §3.5), synchronously and without I/O: the same
 * layers [PolicySetLoader] builds from repositories holding the same data (§8.4 P5).
 *
 * Every layer is always present, in the order GLOBAL, PRINCIPAL, ROLE. The global layer is the document's, built once;
 * the principal layer is a fresh list of the compiled groups; the role layer is one [AppPermissionGroup] over the
 * compiled rules and grants, so its per-request memos (unresolved grants, grant positions beyond the shared cache)
 * are the same as [PolicySetLoader]'s. Nothing handed to the engine is changed after it is built.
 *
 * Every document comes from [snapshot], which holds one immutable revision of each (§8.4 P7).
 */
internal object SnapshotPolicySetLoader {
    fun load(reads: SnapshotReads, snapshot: PolicySnapshot): PolicySet {
        val policies = snapshot.policies
        return PolicySet(listOf(global(policies), principal(reads, policies), role(reads, snapshot)))
    }

    private fun global(policies: DocumentState<CompiledPolicies>): LayerInput = when (policies) {
        is DocumentState.Present -> policies.compiled.globalLayer
        // As 7.0's repository with no policy stored.
        DocumentState.Absent -> PolicySnapshotCompiler.NO_GLOBAL_POLICIES
        is DocumentState.Failed -> LayerInput.Unavailable(LayerKind.GLOBAL, policies.error)
    }

    private fun principal(reads: SnapshotReads, policies: DocumentState<CompiledPolicies>): LayerInput {
        reads.principalLayer?.let { return it }
        val policyIds = reads.policyIds
        val byId = when (policies) {
            is DocumentState.Present -> policies.compiled.byId
            DocumentState.Absent -> emptyMap()
            is DocumentState.Failed -> return LayerInput.Unavailable(LayerKind.PRINCIPAL, policies.error)
        }
        val groups = ArrayList<PolicyGroup>(policyIds.size)
        var missing: LinkedHashSet<PolicyId>? = null
        for (policyId in policyIds) {
            when (val compiled = byId[policyId]) {
                // 7.0 reads the principal's policies at once: one that cannot be decoded fails the whole read.
                is Compiled.Broken -> return LayerInput.Unavailable(LayerKind.PRINCIPAL, compiled.error)
                is Compiled.Ok -> groups.add(compiled.value)
                null -> (missing ?: LinkedHashSet<PolicyId>().also { missing = it }).add(policyId)
            }
        }
        missing?.let {
            return LayerInput.Loaded(
                LayerKind.PRINCIPAL,
                emptyList(),
                Cause.MissingPolicies(it, MissingPolicyException(it))
            )
        }
        return LayerInput.Loaded(LayerKind.PRINCIPAL, groups)
    }

    private fun role(reads: SnapshotReads, snapshot: PolicySnapshot): LayerInput {
        reads.roleLayer?.let { return it }
        val appId = checkNotNull(reads.appId)
        val appEntry = snapshot.appEntry
        // A snapshot taken for another app (or none) is a caller bug; fail the layer rather than read the wrong grants.
        if (appEntry == null || (appEntry.key as? SnapshotKey.App)?.appId != appId) {
            return LayerInput.Unavailable(
                LayerKind.ROLE,
                IllegalStateException("The snapshot holds ${appEntry?.key} instead of the document of app [$appId].")
            )
        }
        val app = when (val state = appEntry.state) {
            is DocumentState.Present -> state.compiled
            DocumentState.Absent -> return NO_APP_PERMISSION
            is DocumentState.Failed -> return LayerInput.Unavailable(LayerKind.ROLE, state.error)
        }
        val rules = when (val appPermission = app.appPermission) {
            is Compiled.Ok -> appPermission.value
            is Compiled.Broken -> return LayerInput.Unavailable(LayerKind.ROLE, appPermission.error)
        }
        val spaceGrants = app.grants[reads.spaceId].orEmpty()
        // In the principal's role order, skipping roles without grants, as 7.0's repository does.
        val rolePermissions = ArrayList<RolePermission>(reads.scopedRoles.size)
        for (roleId in reads.scopedRoles) {
            when (val grant = spaceGrants[roleId]) {
                is Compiled.Broken -> return LayerInput.Unavailable(LayerKind.ROLE, grant.error)
                is Compiled.Ok -> rolePermissions.add(grant.value)
                null -> Unit
            }
        }
        return LayerInput.Loaded(LayerKind.ROLE, listOf(AppPermissionGroup(rules, rolePermissions)))
    }
}

private val NO_PRINCIPAL_POLICIES: LayerInput = LayerInput.Absent(LayerKind.PRINCIPAL, AbsentReason.NO_POLICIES)
private val NO_ROLES: LayerInput = LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_ROLES)
private val NO_ROLE_IN_SPACE: LayerInput = LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_ROLE_IN_SPACE)
private val NO_APP_PERMISSION: LayerInput = LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_APP_PERMISSION)

private fun unavailable(kind: LayerKind, error: Throwable): LayerInput {
    Exceptions.throwIfJvmFatal(error)
    return LayerInput.Unavailable(kind, error)
}
