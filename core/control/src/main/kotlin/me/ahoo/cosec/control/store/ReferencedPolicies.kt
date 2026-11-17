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
import me.ahoo.cosec.control.store.RevisionLog.log
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one question `CTL-30` asks, for the check of a change and for the head write alike: which policies of the base
 * does a platform or app candidate no longer have, or have as the other kind (a policy or a boundary), that other
 * documents still refer to. It lives here, beside the index it reads, because the write is the authority and the check
 * (`lifecycle`, which depends on this package) must never answer differently: both call [of] with their own reads.
 */
internal object ReferencedPolicies {
    /**
     * The policies of [base] that [candidate], a document of [key], removes or turns from a policy into a boundary or
     * back (RFC 0007 §5.8), with the documents that still refer to each, as [referrers] says; empty for none. A tenant
     * candidate, and a first revision, change nothing for anyone. A tenant whose head ([head]) defines the id itself
     * refers to its own policy, not the platform's, and does not block (RC1-9); one that cannot be read may refer.
     *
     * Both documents are read [expanded][RoleExpansion.effective]: what a short form derives is a policy like any
     * other, and tenants' roles may refer to it (RFC 0012 §7.5).
     */
    suspend fun of(
        key: SnapshotKey,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        referrers: suspend (SnapshotKey, PolicyId) -> Set<SnapshotKey>,
        head: suspend (SnapshotKey) -> Head?,
    ): Map<PolicyId, Set<SnapshotKey>> {
        if (base == null || key is SnapshotKey.Tenant) {
            return emptyMap()
        }
        val kept = RoleExpansion.effective(candidate).policies.associate { it.id to it.boundary }
        return RoleExpansion.effective(base).policies.filter { kept[it.id] != it.boundary }.map { it.id }
            .associateWith { policy ->
                (referrers(key, policy) - key).filterNotTo(
                    HashSet()
                ) { definesItself(it, policy, head) }
            }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Whether [referrer] is a tenant whose head, read, defines [policy] itself and does not name it as its boundary;
     * one that cannot be read may refer.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun definesItself(
        referrer: SnapshotKey,
        policy: PolicyId,
        head: suspend (SnapshotKey) -> Head?
    ): Boolean = try {
        referrer is SnapshotKey.Tenant && head(referrer)?.document?.let { document ->
            document.boundary != policy && document.policies.any { it.id == policy }
        } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The document of [$referrer] could not be read; it counts as referring to [${policy.value}]." }
        false
    }
}
