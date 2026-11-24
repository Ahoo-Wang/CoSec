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
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ControlStatus.UNAVAILABLE
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/** What a platform candidate does to the documents that share its ids (RFC 0007 N2, RC1-9, §9.4). */
internal object PlatformChecks {
    private val log = KotlinLogging.logger {}

    /**
     * The policy ids a platform candidate adds that a tenant document already uses, defining or binding it (RFC 0007
     * N2, RC1-9, `CTL-43`): an id the tenant defines would be ambiguous in that tenant, which the engine fails closed
     * on; one it binds without defining, which only a head not written through a compiled change can hold (as `cosec-migrate` writes one), would be re-pointed to the platform's, since
     * the engine resolves a binding without a policy of its document to the platform's policy before the app's
     * (`CandidateOrder.resolve`). Every tenant with an entry, whatever its flag, is a candidate, confirmed by reading
     * its head: an entry a lost race or an earlier revision left does not block, and needs no repair to stop blocking.
     * One whose head cannot be read is refused (503), and so is every id whose back-references cannot be read (`CTL-42`).
     */
    suspend fun sharedIds(
        base: PolicyDocument?,
        candidate: PolicyDocument,
        context: CurrentDocuments,
        findings: MutableList<Finding>,
    ) {
        val existing = base?.policies.orEmpty().associateBy { it.id }
        for ((i, policy) in candidate.policies.withIndex()) {
            if (policy.id in existing) {
                changedWithCopies(existing.getValue(policy.id), policy, i, context, findings)
                continue
            }
            val tenants = tenants(context, policy.id, onlyDefining = false)
            if (tenants == null) {
                findings += Finding(
                    "V2",
                    "$.policies[$i].id",
                    "[${policy.id.value}] may be a policy id a tenant already uses: its back-references could not be read",
                    UNAVAILABLE,
                )
                continue
            }
            val uses = tenants.associateWith { uses(context, it, policy.id) }
            val using = uses.filterValues { it != false }.keys
            if (using.isEmpty()) continue
            val status = if (uses.values.any { it == null }) UNAVAILABLE else UNPROCESSABLE
            findings += Finding(
                "V2",
                "$.policies[$i].id",
                "[${policy.id.value}] is a policy id ${using.map { it.toString() }.sorted()} already use" +
                    if (status == UNAVAILABLE) ", or whose document could not be read" else "",
                status,
            )
        }
    }

    /**
     * The tenants with an entry for the platform's [policy], only those whose entry says they define it themselves when
     * [onlyDefining]; `null` when its references cannot be read.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun tenants(
        context: CurrentDocuments,
        policy: PolicyId,
        onlyDefining: Boolean,
    ): Set<SnapshotKey.Tenant>? = try {
        context.referrers(SnapshotKey.Platform, policy).filter { !onlyDefining || it.definesItself }
            .mapNotNullTo(LinkedHashSet()) { it.referrer as? SnapshotKey.Tenant }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(
            e
        ) { "The back-references of [${policy.value}] could not be read; it is refused as an id a tenant may use." }
        null
    }

    /**
     * A platform policy that tenants hold exact copies of (the partition flow, RFC 0007 §9.4) is frozen while they do:
     * changing it would leave the two layers with one id and two contents, which the engine refuses. A tenant whose
     * head cannot be read counts as holding a copy.
     */
    private suspend fun changedWithCopies(
        before: PolicyDefinition,
        after: PolicyDefinition,
        index: Int,
        context: CurrentDocuments,
        findings: MutableList<Finding>,
    ) {
        if (StatementDigest.ofCopy(before) == StatementDigest.ofCopy(after)) return
        // Only a tenant whose entry says it defines the id can hold a copy; its content is compared on its head. One
        // whose references cannot be read is refused as copied (CTL-42), never let through.
        val tenants = tenants(context, after.id, onlyDefining = true) ?: run {
            findings += Finding(
                "V2",
                "$.policies[$index].id",
                "[${after.id.value}] may be copied by a tenant: its back-references could not be read",
                UNAVAILABLE,
            )
            return
        }
        val holding = tenants.associateWith { definesCopy(context, it, after.id) }.filterValues { it != false }
        if (holding.isEmpty()) return
        val status = if (holding.values.any { it == null }) UNAVAILABLE else UNPROCESSABLE
        findings += Finding(
            "V2",
            "$.policies[$index].id",
            "[${after.id.value}] is copied by ${holding.keys.map { it.toString() }.sorted()}: change it only after their copies are removed" +
                if (status == UNAVAILABLE) ", or whose document could not be read" else "",
            status,
        )
    }

    /** Whether the head of [tenant] defines or binds [policy], as written out; `null` when it cannot be read. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun uses(context: CurrentDocuments, tenant: SnapshotKey.Tenant, policy: PolicyId): Boolean? = try {
        context.head(tenant)?.document.let { document ->
            document != null && RoleExpansion.effective(document).let { effective ->
                effective.policies.any { it.id == policy } || effective.bindings.any { it.policy == policy }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The document of [$tenant] could not be read; [${policy.value}] is refused as one it may use." }
        null
    }

    /** Whether the head of [tenant] defines [policy] itself; `null` when it cannot be read. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun definesCopy(
        context: CurrentDocuments,
        tenant: SnapshotKey.Tenant,
        policy: PolicyId
    ): Boolean? = try {
        context.head(tenant)?.document?.policies?.any { it.id == policy } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The document of [$tenant] could not be read; [${policy.value}] is refused as one it may copy." }
        null
    }
}
