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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.document.DocumentCompileException
import me.ahoo.cosec.document.Expansion
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** What the bindings of an app or tenant candidate resolve to (RFC 0007 §3.1 N1, §7.6). */
internal object BindingChecks {
    private const val UNPROCESSABLE = 422
    private val BUILTIN = BuiltinPolicies.CONTROL.keys.map { it.value } + BuiltinPolicies.ROOT.value

    /**
     * Bindings of an app or tenant candidate whose policy is defined neither by it nor by the platform, nor, for a
     * tenant binding of exactly one app, by that app's document (RFC 0007 §3.1 N1). Both are read expanded (RFC 0012
     * §6.1): a binding a role derives must resolve, in what the app's short forms derive too. A platform boundary is no
     * policy a binding reaches (RFC 0007 §7.6): the engine would answer it as missing.
     */
    suspend fun unresolved(
        candidate: PolicyDocument,
        platform: PolicyDocument?,
        context: ChangeChecks.Context,
        checkedWith: MutableMap<SnapshotKey, SnapshotDigest?>,
        findings: MutableList<Finding>,
    ) {
        // Over the limit the compile step has said so, and there is nothing expanded to look at.
        val expansion = expansionOf(candidate) ?: return
        val effective = expansion.document
        val (boundaries, policies) = platform?.policies.orEmpty().partition { it.boundary }
        val defined = effective.policies.map { it.id.value }.toSet() + policies.map { it.id.value } + BUILTIN
        val boundaryIds = boundaries.mapTo(HashSet()) { it.id.value }
        val apps = HashMap<String, Set<String>>()
        for ((i, binding) in effective.bindings.withIndex()) {
            val policy = binding.policy.value
            if (policy in defined || inApp(candidate, binding, apps, context, checkedWith)) continue
            val written = i < candidate.bindings.size
            val path = expansion.bindingPath(i) + if (written) ".policy" else ""
            val message = if (policy in boundaryIds) {
                "[$policy] is a boundary of the platform, and a boundary is not bound"
            } else {
                "[$policy] is defined neither here nor where this document may refer"
            }
            findings += Finding("REFERENCES", path, message, UNPROCESSABLE)
        }
        RoleChecks.roleWarnings(candidate, context, checkedWith, findings)
        val boundary = candidate.boundary ?: return
        if (platform?.policies?.none { it.id == boundary && it.boundary } != false) {
            findings += Finding("REFERENCES", "$.boundary", "[${boundary.value}] is not a boundary of the platform", UNPROCESSABLE)
        }
    }

    /**
     * Whether [binding] of a tenant [candidate], scoped to one app, names a policy that app's document defines, or an app
     * no key can name, which V2 already refuses (appIds): it has no document to resolve in.
     */
    private suspend fun inApp(
        candidate: PolicyDocument,
        binding: Binding,
        apps: MutableMap<String, Set<String>>,
        context: ChangeChecks.Context,
        checkedWith: MutableMap<SnapshotKey, SnapshotDigest?>,
    ): Boolean {
        val app = (binding.scope.app as? Match.Exactly)?.value?.value
        if (app != null && !SnapshotKey.App.isValidAppId(app)) return true
        return candidate.kind == DocumentKind.TENANT && app != null && binding.policy.value in apps.getOrPut(app) {
            val head = context.head(SnapshotKey.App(app))
            checkedWith[SnapshotKey.App(app)] = head?.digest
            head?.document?.let(RoleExpansion::effective)?.policies?.map { it.id.value }?.toSet().orEmpty()
        }
    }

    /** [candidate] expanded; `null` when it derives more than the limit, which its compile finding reports. */
    private fun expansionOf(candidate: PolicyDocument): Expansion? = try {
        RoleExpansion.expand(candidate)
    } catch (_: DocumentCompileException) {
        null
    }
}
