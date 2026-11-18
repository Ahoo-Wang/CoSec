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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.DocumentKind

/**
 * A slot for each policy id several of [bindings] name, in binding order; none for an id bound once. Two bindings of
 * one document that name an id and both resolve resolve to the same policy: the platform's (a tenant's own copy of
 * it resolves to the platform's too), else the document's own, else that of the one app both are scoped to
 * ([CandidateOrder]), since one scoped to any app finds only the platform's.
 */
internal fun repeatSlots(bindings: List<Binding>): Map<String, Int> {
    if (bindings.size < 2) {
        return emptyMap()
    }
    val counts = HashMap<String, Int>(bindings.size * 2)
    for (binding in bindings) {
        counts.merge(binding.policy.value, 1, Int::plus)
    }
    val slots = HashMap<String, Int>()
    for (binding in bindings) {
        val id = binding.policy.value
        if (counts.getValue(id) > 1 && id !in slots) {
            slots[id] = slots.size
        }
    }
    return slots
}

internal fun boundEntry(
    kind: DocumentKind,
    owner: String,
    policies: Map<String, CompiledPolicy>,
    boundaries: Map<String, CompiledPolicy>,
    binding: Binding,
    repeat: Int,
): BoundEntry {
    // The candidate order reads role bindings from app and tenant documents and everything else from platform and
    // tenant documents (RFC 0004 §4.3, §6.3). A binding anywhere else would never be a candidate: a DENY lost.
    when (kind) {
        DocumentKind.PLATFORM -> require(binding.subject !is SubjectRef.Role) {
            "Role binding ${binding.id.value} in the platform document: role grants belong in app documents"
        }

        DocumentKind.APP -> require(binding.subject is SubjectRef.Role) {
            "Binding ${binding.id.value} of the app document $owner does not bind a role"
        }

        DocumentKind.TENANT -> Unit
    }
    val policyId = binding.policy.value
    require(policyId != BuiltinPolicies.BLACKLIST.value) { "Binding ${binding.id.value} binds the blacklist rule" }
    if (policyId == BuiltinPolicies.ROOT.value) {
        require(binding.breakGlass) { "Binding ${binding.id.value} binds $policyId without being break-glass" }
    }
    if (binding.breakGlass) {
        require(kind == DocumentKind.PLATFORM) { "Break-glass binding ${binding.id.value} in the $kind document" }
        require(binding.subject is SubjectRef.Principal) {
            "Break-glass binding ${binding.id.value} must bind a principal"
        }
        requireNotNull(binding.expiresAt) { "Break-glass binding ${binding.id.value} has no expiry" }
        // RFC 0004 §4.8, RFC 0007 §8: root is for the platform's own administrators, never a tenant's principal.
        require((binding.subject as SubjectRef.Principal).tenant == TenantId.PLATFORM) {
            "Break-glass binding ${binding.id.value} must bind a principal of the platform tenant"
        }
    }
    requireReachable(kind, owner, binding)
    val local = if (policyId == BuiltinPolicies.ROOT.value) BuiltinPolicies.root else policies[policyId]
    require(policyId !in boundaries) { "Binding ${binding.id.value} binds boundary policy $policyId" }
    require(local != null || kind != DocumentKind.PLATFORM) {
        "Binding ${binding.id.value} of the platform document names policy $policyId, which it does not define"
    }
    return BoundEntry(
        via = BindingRef.Bound(binding.id, kind),
        policyId = policyId,
        local = local,
        // A tenant's own policy is unambiguous while the platform has no different policy of that id (RFC 0007 N2).
        checksPlatform = kind == DocumentKind.TENANT && local != null,
        app = binding.scope.app.exactApp(),
        space = binding.scope.space.exactSpace(),
        roleTenant = (binding.subject as? SubjectRef.Role)?.tenant?.value,
        expiresAt = binding.expiresAt ?: Long.MAX_VALUE,
        repeat = repeat,
    )
}

/**
 * A tenant document is read only for its own tenant and an app document only for its own app ([SnapshotView]), so
 * a binding naming another one would never be a candidate: a DENY lost.
 */
private fun requireReachable(kind: DocumentKind, owner: String, binding: Binding) {
    when (kind) {
        DocumentKind.PLATFORM -> Unit
        DocumentKind.APP -> require(binding.scope.app.exactApp().let { it == null || it == owner }) {
            "Binding ${binding.id.value} of the app document $owner is scoped to another app"
        }

        DocumentKind.TENANT -> {
            val tenant = when (val subject = binding.subject) {
                is SubjectRef.Principal -> subject.tenant
                is SubjectRef.Role -> subject.tenant
                SubjectRef.Anyone -> null
            }
            require(tenant == null || tenant.value == owner) {
                "Binding ${binding.id.value} of the tenant document $owner binds a subject of tenant ${tenant?.value}"
            }
        }
    }
}

// Plain comparators: kotlin.comparisons is not among the packages the engine may use (EnginePurityTest).
private val BY_ID = Comparator<BoundEntry> { a, b -> a.via.id.value.compareTo(b.via.id.value) }
internal val BY_POLICY_THEN_ID = Comparator<BoundEntry> { a, b ->
    val byPolicy = a.policyId.compareTo(b.policyId)
    if (byPolicy != 0) byPolicy else BY_ID.compare(a, b)
}

internal fun HashMap<String, HashMap<String, ArrayList<BoundEntry>>>.sortedById() =
    mapValues { tenant -> tenant.value.mapValues { it.value.sortedWith(BY_ID).toTypedArray() } }

private fun Match<AppId>.exactApp(): String? =
    when (this) {
        Match.Any -> null
        is Match.Exactly -> value.value
    }

private fun Match<SpaceId>.exactSpace(): String? =
    when (this) {
        Match.Any -> null
        is Match.Exactly -> value.value
    }
