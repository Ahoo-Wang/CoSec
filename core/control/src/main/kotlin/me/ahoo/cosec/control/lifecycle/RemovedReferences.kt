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
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.store.ReferencedPolicies
import me.ahoo.cosec.document.DocumentCompileException
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** What a platform or app candidate does to the documents that refer to its policies (RFC 0007 §5.8, `CTL-30`). */
internal object RemovedReferences {
    /**
     * The policies a platform or app candidate removes, or turns from a policy into a boundary or back (`CTL-30`), that
     * other documents still refer to (RFC 0007 §5.8): a binding reaches only a policy and a tenant's `boundary` only a
     * boundary, so one of the other kind is missing to them. The head write asks the same question
     * ([ReferencedPolicies.of]): a change refused there is refused here, with the same documents. Over the limit of
     * what the roles derive the compile step has said so, and there is nothing expanded to compare.
     */
    suspend fun check(
        key: SnapshotKey,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        context: CurrentDocuments,
        findings: MutableList<Finding>,
    ) {
        val referenced = try {
            ReferencedPolicies.of(key, base, candidate, context::referrers, context::head)
        } catch (_: DocumentCompileException) {
            return
        }
        if (referenced.isEmpty()) return
        val kept = RoleExpansion.effective(candidate).policies.mapTo(HashSet()) { it.id }
        for ((policy, referrers) in referenced) {
            val what = if (policy in kept) "changes between a policy and a boundary, and is" else "is"
            findings += Finding("REFERENCES", "$.policies", "[${policy.value}] $what referred to by ${referrers.sorted()}", UNPROCESSABLE)
        }
    }

    private fun Set<SnapshotKey>.sorted(): List<String> = map { it.toString() }.sorted()
}
