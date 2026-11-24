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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.change.ControlStatus.UNAVAILABLE
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.store.ReferencedPolicies
import me.ahoo.cosec.control.store.ReferrerNames
import me.ahoo.cosec.document.DocumentCompileException
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/** What a platform or app candidate does to the documents that refer to its policies (RFC 0007 §5.8, `CTL-30`). */
internal object RemovedReferences {
    private val log = KotlinLogging.logger {}

    /**
     * The policies a platform or app candidate removes, or turns from a policy into a boundary or back (`CTL-30`), that
     * other documents still refer to (RFC 0007 §5.8): a binding reaches only a policy and a tenant's `boundary` only a
     * boundary, so one of the other kind is missing to them. The head write asks the same question
     * ([ReferencedPolicies.of]): a change refused there is refused here, with the same documents. A candidate over the
     * limit of what the roles derive the compile step has said so, and there is nothing expanded to compare. A base over
     * it (a head `cosec-migrate` or a restore wrote) is refused here by name, or the change would be `CHECKED` and fail
     * at its head write (DR1-E-10).
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun check(
        key: SnapshotKey,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        context: CurrentDocuments,
        findings: MutableList<Finding>,
    ) {
        val referenced = try {
            ReferencedPolicies.of(key, base, candidate, context::referrers)
        } catch (e: DocumentCompileException) {
            if (expands(candidate)) {
                findings += Finding(
                    "REFERENCES",
                    "$.policies",
                    "The base revision of [$key] does not expand, so what this change removes cannot be compared with " +
                        "what refers to it: ${e.message}",
                    UNPROCESSABLE,
                )
            }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A back-reference set that cannot be read may name a referrer: the removal is refused (CTL-42).
            log.warn(e) { "[$key]: the back-references of its policies could not be read; the change is refused." }
            findings += Finding("REFERENCES", "$.policies", "The back-references could not be read: ${e.message}", UNAVAILABLE)
            return
        }
        if (referenced.isEmpty()) return
        val kept = RoleExpansion.effective(candidate).policies.mapTo(HashSet()) { it.id }
        for ((policy, referrers) in referenced) {
            val what = if (policy in kept) "changes between a policy and a boundary, and is" else "is"
            findings += Finding("REFERENCES", "$.policies", "[${policy.value}] $what referred to by ${ReferrerNames.of(referrers)}", UNPROCESSABLE)
        }
    }

    private fun expands(document: PolicyDocument): Boolean = try {
        RoleExpansion.effective(document)
        true
    } catch (_: DocumentCompileException) {
        false
    }
}
