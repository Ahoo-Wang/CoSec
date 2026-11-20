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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The one question `CTL-30` asks, for the check of a change and for the head write alike: which policies of the base
 * does a platform or app candidate no longer have, or have as the other kind (a policy or a boundary), that other
 * documents still refer to. It lives here, beside the index it reads, because the write is the authority and the check
 * (`lifecycle`, which depends on this package) must never answer differently: both call [of] with their own reads.
 */
internal object ReferencedPolicies {
    /**
     * The policies of [base] that [candidate], a document of [key], removes or turns from a policy into a boundary or
     * back (RFC 0007 §5.8), with the documents that still refer to each, as the entries [referrers] reads say; empty for
     * none. A tenant candidate, and a first revision, change nothing for anyone.
     *
     * A tenant that defines the id itself refers to its own policy, not the platform's, and does not block (RC1-9).
     * Its entries say so (`CTL-43`), so no head is read: a referrer blocks unless every entry of it defines the id
     * itself. An entry a later revision or a lost race left errs towards blocking. An app's policies are its permission
     * policies, which no tenant can define (`TNT-2`): every referrer of one blocks. A value that cannot be read throws
     * (`CTL-42`), so the removal is refused, never let through.
     *
     * Both documents are read [expanded][RoleExpansion.effective]: what a short form derives is a policy like any
     * other, and tenants' roles may refer to it (RFC 0012 §7.5).
     */
    suspend fun of(
        key: SnapshotKey,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        referrers: suspend (SnapshotKey, PolicyId) -> Set<ReferrerEntry>,
    ): Map<PolicyId, Set<SnapshotKey>> {
        if (base == null || key is SnapshotKey.Tenant) {
            return emptyMap()
        }
        val kept = RoleExpansion.effective(candidate).policies.associate { it.id to it.boundary }
        return RoleExpansion.effective(base).policies.filter { kept[it.id] != it.boundary }.map { it.id }
            .associateWith { policy -> blocking(key, referrers(key, policy)) }
            .filterValues { it.isNotEmpty() }
    }

    /** The referrers among [entries] that block a removal from [key] (see [of]). */
    fun blocking(key: SnapshotKey, entries: Set<ReferrerEntry>): Set<SnapshotKey> =
        entries.filter { it.referrer != key }.groupBy { it.referrer }
            .filterValues { of -> key is SnapshotKey.App || !of.all { it.definesItself } }
            .keys
}
