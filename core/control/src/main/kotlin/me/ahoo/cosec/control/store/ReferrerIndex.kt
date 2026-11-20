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
import me.ahoo.cosec.document.PolicyDocumentCompiler
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
 * policy still referred to is not removed. Each reference is an entry of its own ([ReferrerEntry]): who refers, the
 * revision that indexed it, and whether that revision defines the id itself (RC1-9).
 *
 * [Revisions.publish] records the entries a candidate adds in the document's own revision index ([IndexedReference]),
 * then adds them, all before its head write; after the write it removes, by that record, the entries the published
 * revision no longer has. A tenant's publish thus reads and writes its own index and its own entries, never the
 * references of others (`CTL-44`). `repair-references` adds what is missing and prunes what no head has, member by
 * member, never replacing a set (`MIG-4`).
 */
internal class ReferrerIndex(
    private val store: ControlStore,
    private val writes: RevisionWrites,
    /** The head of a document, as [Revisions.head] reads it. */
    private val head: suspend (SnapshotKey) -> Head?,
) {
    /** A reference of a document: [policy] of [target], and whether the document defines that id itself (RC1-9). */
    data class Reference(val target: SnapshotKey, val policy: PolicyId, val definesItself: Boolean = false) {
        fun at(revision: Long): IndexedReference = IndexedReference(target, policy, revision, definesItself)
    }

    /** The documents indexed as referring to [policy] of [target] (RFC 0007 §5.8). */
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> =
        store.referrers(target, policy).mapTo(LinkedHashSet()) { it.referrer }

    /** The entries of the references to [policy] of [target]; a value that cannot be read throws (`CTL-42`). */
    suspend fun entries(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry> = store.referrers(target, policy)

    /**
     * Adds the entries of [added], references of [key] at [revision], once its revision index records them (§5.4 step
     * 3). A failure to add one throws, and the head is not moved: a reference missing from the index would let a
     * deletion through. An entry the record does not hold stays indexed until `pruneReferences` removes it, which
     * blocks a removal at most.
     */
    suspend fun addEntries(key: SnapshotKey, revision: Long, added: Set<Reference>) {
        for (reference in added) {
            store.addReferrer(reference.target, reference.policy, ReferrerEntry(key, revision, reference.definesItself))
        }
    }

    /**
     * [addEntries], then their record in [key]'s revision index (`repair-references`): the other order from a publish's.
     * A publish records before it adds and moves the head only after every add, so a recorded reference it failed to add
     * is not in the head. The repair adds for a head already written: had it recorded first and failed part-way, the
     * next run would read the record and add nothing. Adding first, a run that dies before the record leaves members
     * the next run adds again (an idempotent write at the same revision) or, at worst, one [pruneReferences] removes.
     */
    private suspend fun addAndRecord(key: SnapshotKey, revision: Long, added: Set<Reference>) {
        if (added.isEmpty()) {
            return
        }
        addEntries(key, revision, added)
        writes.updateIndex(key) { it.copy(indexed = it.indexed + added.map { reference -> reference.at(revision) }) } ||
            warn("[$key] revision $revision: what it indexed could not be recorded; the next repair adds it again")
    }

    /**
     * After [key]'s head moved to [revision] (§5.4 step 6): removes the entries its record holds at or below that
     * revision whose reference the published document no longer has ([kept]), then the record of them. It reads [key]'s
     * own index, never the references of others. A later revision's entry is another entry, and stays (`CTL-25`); so
     * does the record of an entry whose removal failed, for the next publish to remove. Failures only warn.
     */
    suspend fun dropGone(key: SnapshotKey, revision: Long, kept: Set<Reference>) {
        val recorded = store.index(key)?.value?.indexed.orEmpty()
        val gone = recorded.filter { it.revision <= revision && Reference(it.target, it.policy, it.definesItself) !in kept }
        if (gone.isEmpty()) {
            return
        }
        val removed = gone.filter { indexed ->
            try {
                store.removeReferrers(indexed.target, indexed.policy, setOf(indexed.entryOf(key)))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                log.warn(
                    e
                ) { "[$key] no longer refers to [${indexed.policy.value}] of [${indexed.target}], which stays indexed" }
                false
            }
        }.toSet()
        if (removed.isNotEmpty()) {
            writes.updateIndex(key) { it.copy(indexed = it.indexed - removed) } ||
                warn("[$key] revision $revision: the record of what it dropped could not be updated")
        }
    }

    /** Whether [referrer] is a tenant whose head defines [policy] itself (RC1-9): its entries say `#own`. */
    suspend fun definesItself(referrer: SnapshotKey, policy: PolicyId): Boolean =
        referrer is SnapshotKey.Tenant &&
            head(referrer)?.document?.let { document -> RoleExpansion.effective(document).policies.any { it.id == policy } } == true

    /**
     * The back-reference sets the head of [key] touches: those of what it refers to and, for the platform or an app,
     * those of its own policies. What `cosec-migrate repair-references` converts from the format before 8.0.0-beta.5:
     * a set no head touches blocks nothing. Empty for a document with no head.
     */
    suspend fun referenceKeys(key: SnapshotKey): Set<Pair<SnapshotKey, PolicyId>> {
        val document = head(key)?.document ?: return emptySet()
        val referred = references(document).map { it.target to it.policy }
        val own = if (key is SnapshotKey.Tenant) {
            emptyList()
        } else {
            RoleExpansion.effective(
                document
            ).policies.map { key to it.id }
        }
        return (referred + own).toCollection(LinkedHashSet())
    }

    /**
     * Adds whatever the head of [key] refers to and its own forward record does not hold (`repair-references`, RFC 0007
     * §5.8), adding the members first and recording them after; the number of references it added. It reads [key]'s head and index, never a
     * back-reference set (R2C-8): a document whose record holds its references costs those two reads, and one with no
     * record, such as one converted from the format before 8.0.0-beta.5, adds each of its own references once, an
     * idempotent member write, so a run costs what the documents hold, not what refers to them. It rests on one
     * invariant: a recorded reference is in its set, or not in the head. A publish keeps it (record, add, then the head
     * only after every add), [dropGone] keeps it (the head no longer has what it drops), and so does this repair, which
     * records only what it has added ([addAndRecord]). A missing entry would let
     * a deletion through. A head or a value that cannot be read throws. Entries are only ever added here, never a set
     * replaced (`MIG-4`): a publish that adds an entry meanwhile keeps it. What the index has and no head does is
     * [pruneReferences]'s.
     */
    suspend fun repairReferences(key: SnapshotKey): Int {
        val head = head(key) ?: return 0
        val recorded = store.index(key)?.value?.indexed.orEmpty()
            .mapTo(HashSet()) { Reference(it.target, it.policy, it.definesItself) }
        val missing = references(head.document) - recorded
        addAndRecord(key, head.revision, missing)
        return missing.size
    }

    /**
     * Removes from the back-reference index, for every policy the head of [target] defines, the entries no head has
     * (`repair-references`, RFC 0007 §5.8): what a publish indexed before a head write it then lost, which would block
     * removing that policy for good. The number removed. Each is removed as the exact entry it read, so an entry a
     * publish adds meanwhile is never taken (`MIG-4`), and the index stays a superset of the true references:
     *
     * - an entry goes only when its referrer's head is at or above the revision that indexed it, and does not have the
     *   reference. The publish that indexed it is then over: one still in flight has indexed a revision above the head;
     * - a referrer whose head is not there or cannot be read keeps its entries: it may have the reference.
     *
     * The policies [target] does not define are not looked at: an entry of one blocks nothing.
     */
    suspend fun pruneReferences(target: SnapshotKey, heads: ReferrerHeads = ReferrerHeads()): Int {
        val document = head(target)?.document ?: return 0
        var removed = 0
        for (policy in RoleExpansion.effective(document).policies.map { it.id }) {
            val entries = store.referrers(target, policy)
            entries.forEach { referring(it.referrer, heads) }
            val gone = entries.filterTo(LinkedHashSet()) { heads.gone(it, target, policy) }
            if (gone.isNotEmpty()) {
                store.removeReferrers(target, policy, gone)
                removed += gone.size
            }
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
                ReferrerHeads.Referring(
                    it?.revision ?: 0,
                    it?.let { head -> references(head.document) }.orEmpty(),
                )
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
     * other documents still refer to; `null` for none. The question is [ReferencedPolicies.of], which the check of a
     * change asks too.
     */
    suspend fun referenced(
        key: SnapshotKey,
        base: Head?,
        candidate: PolicyDocument
    ): PublishOutcome.Referenced? =
        ReferencedPolicies.of(key, base?.document, candidate, ::entries)
            .takeIf { it.isNotEmpty() }?.let { PublishOutcome.Referenced(it) }

    /**
     * What [document] refers to in other documents: a binding to a policy it does not define names a platform
     * policy, and in a tenant document bound in one app, possibly that app's permission policy (RFC 0007 §3.1 N1).
     * Both are indexed: the index may hold more than the true references, never less. A tenant's boundary names a
     * platform boundary policy (RFC 0007 §7.6).
     *
     * A tenant's own policy ids are indexed as uses of the platform's id too (RC1-9), marked [Reference.definesItself],
     * so that the platform cannot add a policy of an id a tenant already defines, and removing the platform's does not
     * wait for a tenant that has its own (`CTL-43`).
     *
     * The index is of what a document grants, so it reads the [expanded][RoleExpansion.effective] document (RFC 0012
     * §7.5): an app must not remove a permission a tenant's role lists.
     *
     * A tenant's binding to an app's permission policy (`perm:<app>/<id>`), bound in that app, is indexed under the app
     * only. Only that app's document defines the prefix, and the compiler refuses it in any other (`TNT-2`), so the
     * platform can never define, remove or reuse such an id: an entry under the platform could never block anything,
     * and would double what each tenant's publish writes. Every other reference stays indexed as it was.
     */
    fun references(stored: PolicyDocument): Set<Reference> {
        if (stored.kind == DocumentKind.PLATFORM) {
            return emptySet()
        }
        val document = RoleExpansion.effective(stored)
        val own = document.policies.map { it.id }.toSet()
        val references = LinkedHashSet<Reference>()
        if (document.kind == DocumentKind.TENANT) {
            own.forEach { references += Reference(SnapshotKey.Platform, it, definesItself = true) }
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
            targetsOf(document.kind, app, binding.policy).forEach { references += Reference(it, binding.policy) }
        }
        document.boundary?.let { references += Reference(SnapshotKey.Platform, it) }
        return references
    }

    /**
     * Where a binding to [policy], bound in [app] (null for any), of a document of [kind] is indexed: under the platform,
     * and for a tenant's binding in one app under that app too (RFC 0007 §3.1 N1); an app's permission policy under
     * that app alone (see [references]).
     */
    private fun targetsOf(kind: DocumentKind, app: String?, policy: PolicyId): List<SnapshotKey> = when {
        kind != DocumentKind.TENANT || app == null -> listOf(SnapshotKey.Platform)
        isPermissionOf(app, policy) -> listOf(SnapshotKey.App(app))
        else -> listOf(SnapshotKey.Platform, SnapshotKey.App(app))
    }

    /** Whether [policy] is a permission policy of [app] (`perm:<app>/<id>`), which only that app's document defines. */
    private fun isPermissionOf(app: String, policy: PolicyId): Boolean =
        policy.value.startsWith(PolicyDocumentCompiler.permissionPrefix(app))
}
