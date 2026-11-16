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
import me.ahoo.cosec.control.store.RevisionLog.warn
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * The back-reference index of [Revisions] (RFC 0007 §5.8): what a document refers to in others, indexed so that a
 * policy still referred to is not removed. [Revisions.publish] indexes what a candidate adds before its head write and
 * drops what it no longer refers to after; `repair-references` adds what is missing and prunes what no head has.
 */
internal class ReferrerIndex(
    private val store: ControlStore,
    private val writes: RevisionWrites,
    /** The head of a document, as [Revisions.head] reads it. */
    private val head: suspend (SnapshotKey) -> Head?,
) {
    /** The documents indexed as referring to [policy] of [target] (RFC 0007 §5.8). */
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> =
        store.referrers(target, policy)?.value?.keys.orEmpty()

    /**
     * Adds whatever the head of [key] refers to and the back-reference index misses (`repair-references`, RFC 0007
     * §5.8); the number of references it added. A missing entry would let a deletion through. A head that cannot be read
     * throws. What the index has and no head does is [pruneReferences]'s.
     */
    suspend fun repairReferences(key: SnapshotKey): Int {
        val head = head(key) ?: return 0
        var added = 0
        for ((target, policy) in references(head.document)) {
            if (store.referrers(target, policy)?.value?.containsKey(key) == true) {
                continue
            }
            check(
                writes.updateReferrers(target, policy) { indexed(it, key, head.revision) }
            ) { "[$key]: the reference to [$policy] of [$target] was not repaired." }
            added++
        }
        return added
    }

    /**
     * Removes from the back-reference index, for every policy the head of [target] defines, the references no head has
     * (`repair-references`, RFC 0007 §5.8): what a publish indexed before a head write it then lost, which would block
     * removing that policy for good. The number removed. It may run while the control plane runs, and the index stays a
     * superset of the true references (the only thing it must be, §5.8):
     *
     * - an entry goes only when its referrer's head is at or above the revision that indexed it, and does not have the
     *   reference. The publish that indexed it is then over: one still in flight has indexed a revision above the head;
     * - a later revision that brings the reference back indexes itself before its head write, so the entry then names a
     *   revision above any head read here. The removal is conditional on the entry as it is, so it leaves that alone;
     * - a referrer whose head is not there or cannot be read keeps its entries: it may have the reference.
     *
     * A publish that lost its race and that no later revision has passed stays indexed until one does. The policies
     * [target] does not define are not looked at: an entry of one blocks nothing.
     */
    suspend fun pruneReferences(target: SnapshotKey, heads: ReferrerHeads = ReferrerHeads()): Int {
        val document = head(target)?.document ?: return 0
        var removed = 0
        for (policy in RoleExpansion.effective(document).policies.map { it.id }) {
            store.referrers(target, policy)?.value?.keys.orEmpty().forEach { referring(it, heads) }
            var gone = 0
            check(
                writes.updateReferrers(target, policy) { current ->
                    current.filterNot { (referrer, revision) -> heads.gone(referrer, revision, target, policy) }
                        .also { gone = current.size - it.size }
                }
            ) { "[$target]: the references to [$policy] kept changing." }
            removed += gone
        }
        return removed
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun referring(referrer: SnapshotKey, heads: ReferrerHeads) {
        if (referrer in heads.read) {
            return
        }
        heads.read[referrer] = try {
            head(referrer).let {
                ReferrerHeads.Referring(it?.revision ?: 0, it?.let { head -> references(head.document) }.orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "The head of [$referrer] could not be read; what it is indexed as referring to stays." }
            ReferrerHeads.Referring(0, emptySet())
        }
    }

    /**
     * The policies a platform or app candidate removes, or turns from a policy into a boundary or back (`CTL-30`), that
     * other documents still refer to; `null` for none. A tenant whose head defines the id itself does not refer to the
     * platform's policy, and does not block (RC1-9).
     */
    suspend fun referenced(
        key: SnapshotKey,
        base: Head?,
        candidate: PolicyDocument
    ): PublishOutcome.Referenced? {
        if (base == null || key is SnapshotKey.Tenant) {
            return null
        }
        // What a short form derives is a policy like any other: tenants' roles may refer to it (RFC 0012 §7.5).
        val kept = RoleExpansion.effective(candidate).policies.associate { it.id to it.boundary }
        val blocked = RoleExpansion.effective(
            base.document
        ).policies.filter { kept[it.id] != it.boundary }.map { it.id }
            .associateWith { policy ->
                val referrers = referrers(key, policy) - key
                referrers.filterNotTo(HashSet()) { definesItself(it, policy) }
            }
            .filterValues { it.isNotEmpty() }
        return if (blocked.isEmpty()) null else PublishOutcome.Referenced(blocked)
    }

    /**
     * Whether [referrer] is a tenant whose head, read, defines [policy] itself and does not name it as its boundary;
     * one that cannot be read may refer.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun definesItself(referrer: SnapshotKey, policy: PolicyId): Boolean = try {
        referrer is SnapshotKey.Tenant && head(referrer)?.document?.let { document ->
            document.boundary != policy && document.policies.any { it.id == policy }
        } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The document of [$referrer] could not be read; it counts as referring to [${policy.value}]." }
        false
    }

    /**
     * What [document] refers to in other documents: a binding to a policy it does not define names a platform
     * policy, and in a tenant document bound in one app, possibly that app's permission policy (RFC 0007 §3.1 N1).
     * Both are indexed: the index may hold more than the true references, never less. A tenant's boundary names a
     * platform boundary policy (RFC 0007 §7.6).
     *
     * A tenant's own policy ids are indexed as uses of the platform's id too (RC1-9), so that the platform cannot add
     * a policy of an id a tenant already defines; readers tell the two apart by the tenant's head.
     *
     * The index is of what a document grants, so it reads the [expanded][RoleExpansion.effective] document (RFC 0012
     * §7.5): an app must not remove a permission a tenant's role lists.
     */
    fun references(stored: PolicyDocument): Set<Pair<SnapshotKey, PolicyId>> {
        if (stored.kind == DocumentKind.PLATFORM) {
            return emptySet()
        }
        val document = RoleExpansion.effective(stored)
        val own = document.policies.map { it.id }.toSet()
        val references = LinkedHashSet<Pair<SnapshotKey, PolicyId>>()
        if (document.kind == DocumentKind.TENANT) {
            own.forEach { references += SnapshotKey.Platform to it }
        }
        for (binding in document.bindings) {
            val app = (binding.scope.app as? Match.Exactly)?.value?.value
            if (app != null && !SnapshotKey.App.isValidAppId(app)) {
                throw SnapshotFormatException(
                    "A document holds binding [${Identifiers.printable(binding.id.value)}] in app " +
                        "[${Identifiers.printable(app)}], which no document key can name (SCP-10)."
                )
            }
            if (binding.policy in own) continue
            references += SnapshotKey.Platform to binding.policy
            if (document.kind == DocumentKind.TENANT && app != null) {
                references += SnapshotKey.App(app) to binding.policy
            }
        }
        document.boundary?.let { references += SnapshotKey.Platform to it }
        return references
    }

    /**
     * [referrers] with [key] at [revision]: a reference [publish] indexes before its head write. One already indexed
     * by [revision] or a later revision (a publish that lost its race) stays as it is.
     */
    fun indexed(referrers: Map<SnapshotKey, Long>, key: SnapshotKey, revision: Long): Map<SnapshotKey, Long> {
        val current = referrers[key]
        return if (current != null && current >= revision) referrers else referrers + (key to revision)
    }

    /**
     * [referrers] without [key], if [revision], which no longer refers, or an earlier revision indexed it. The removal
     * runs after the head write, so a later revision may have indexed the reference again meanwhile (#1207): it stays,
     * and so does one of a revision whose publish lost its race, a superset that blocks a deletion at most.
     */
    fun dropped(referrers: Map<SnapshotKey, Long>, key: SnapshotKey, revision: Long): Map<SnapshotKey, Long> {
        val current = referrers[key] ?: return referrers
        return if (current <= revision) referrers - key else referrers
    }
}
