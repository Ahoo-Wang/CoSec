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

import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.engine.AbsentReason
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotUnavailableException

/**
 * Builds a request's [PolicySet] from a compiled snapshot (RFC 0003 §3.5), synchronously and without I/O: the same
 * layers [PolicySetLoader] builds from repositories holding the same data (§8.4 P5).
 *
 * Every layer is always present, in the order GLOBAL, PRINCIPAL, ROLE. The global layer is the document's, built once;
 * the principal layer is a fresh list of the compiled groups; the role layer is one [AppPermissionGroup] over the
 * compiled rules and grants, so its per-request memos (unresolved grants, grant positions beyond the shared cache)
 * are the same as [PolicySetLoader]'s. Nothing handed to the engine is changed after it is built.
 *
 * A layer that fails gets an error of its own, a [SnapshotUnavailableException] whose cause is the compiled document's
 * shared error: whatever the caller's error handling adds to the error it receives (suppressed exceptions from
 * `block()`, checkpoints) never accumulates on an object every request shares.
 *
 * Every document comes from [snapshot], which holds one immutable revision of each (§8.4 P7).
 */
internal object SnapshotPolicySetLoader {
    private val NO_APP_PERMISSION: LayerInput = LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_APP_PERMISSION)

    fun load(reads: LayerReads, snapshot: PolicySnapshot): PolicySet {
        val policies = snapshot.policies
        return PolicySet(listOf(global(policies), principal(reads, policies), role(reads, snapshot)))
    }

    private fun global(policies: DocumentState<CompiledPolicies>): LayerInput = when (policies) {
        is DocumentState.Present -> when (val layer = policies.compiled.globalLayer) {
            is LayerInput.Unavailable -> unavailable(LayerKind.GLOBAL, SnapshotKey.Policies, layer.error)
            else -> layer
        }
        // As 7.0's repository with no policy stored.
        DocumentState.Absent -> PolicySnapshotCompiler.NO_GLOBAL_POLICIES
        is DocumentState.Failed -> unavailable(LayerKind.GLOBAL, SnapshotKey.Policies, policies.error)
    }

    private fun principal(reads: LayerReads, policies: DocumentState<CompiledPolicies>): LayerInput {
        reads.principalLayer?.let { return it }
        val policyIds = reads.policyIds
        val byId = when (policies) {
            is DocumentState.Present -> policies.compiled.byId
            DocumentState.Absent -> emptyMap()
            is DocumentState.Failed -> return unavailable(LayerKind.PRINCIPAL, SnapshotKey.Policies, policies.error)
        }
        val groups = ArrayList<PolicyGroup>(policyIds.size)
        var missing: LinkedHashSet<PolicyId>? = null
        for (policyId in policyIds) {
            when (val compiled = byId[policyId]) {
                // 7.0 reads the principal's policies at once: one that cannot be decoded fails the whole read.
                is Compiled.Broken -> return unavailable(LayerKind.PRINCIPAL, SnapshotKey.Policies, compiled.error)
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

    private fun role(reads: LayerReads, snapshot: PolicySnapshot): LayerInput {
        reads.roleLayer?.let { return it }
        val appId = reads.appDocument ?: return NO_APP_PERMISSION
        val appEntry = snapshot.appEntry
        // A snapshot taken for another app (or none) is a caller bug; fail the layer rather than read the wrong grants.
        if (appEntry == null || (appEntry.key as? SnapshotKey.App)?.appId != appId) {
            return LayerInput.Unavailable(
                LayerKind.ROLE,
                IllegalStateException("The snapshot holds ${appEntry?.key} instead of the document of app [$appId].")
            )
        }
        val key = appEntry.key
        val app = when (val state = appEntry.state) {
            is DocumentState.Present -> state.compiled
            DocumentState.Absent -> return NO_APP_PERMISSION
            is DocumentState.Failed -> return unavailable(LayerKind.ROLE, key, state.error)
        }
        val rules = when (val appPermission = app.appPermission) {
            is Compiled.Ok -> appPermission.value
            is Compiled.Broken -> return unavailable(LayerKind.ROLE, key, appPermission.error)
        }
        val spaceGrants = app.grants[reads.spaceId].orEmpty()
        // In the principal's role order, skipping roles without grants, as 7.0's repository does.
        val rolePermissions = ArrayList<RolePermission>(reads.scopedRoles.size)
        for (roleId in reads.scopedRoles) {
            when (val grant = spaceGrants[roleId]) {
                is Compiled.Broken -> return unavailable(LayerKind.ROLE, key, grant.error)
                is Compiled.Ok -> rolePermissions.add(grant.value)
                null -> Unit
            }
        }
        return LayerInput.Loaded(LayerKind.ROLE, listOf(AppPermissionGroup(rules, rolePermissions)))
    }

    /** A failed layer with an error of this evaluation's own, caused by the document's [shared] one. */
    private fun unavailable(kind: LayerKind, key: SnapshotKey, shared: Throwable): LayerInput =
        LayerInput.Unavailable(kind, SnapshotUnavailableException(key, "the $kind layer cannot be read", shared))
}
