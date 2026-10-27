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

package me.ahoo.cosec.control.store

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.ParentRevision
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.Provenance
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.cosec.snapshot.document.ShadowWindow
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** A head as the control plane works with it: decoded, with the store's digest. */
class Head(val key: SnapshotKey, val document: PolicyDocument, val stored: StoredDocument) {
    val revision: Long get() = document.revision
    val digest: SnapshotDigest get() = stored.digest
}

/** Who and what publishes a revision (RFC 0007 §5.3); [Revisions.publish] adds the parent. */
class Publication(
    val change: String,
    val kind: ProvenanceKind,
    val publishedBy: Actor,
    val approvedBy: List<Actor> = emptyList(),
    val shadow: ShadowWindow? = null,
)

/** The outcome of [Revisions.publish]. */
sealed interface PublishOutcome {
    /** The head is now [revision]. */
    class Published(val revision: Long, val digest: SnapshotDigest) : PublishOutcome

    /**
     * Someone published since the base was read: nothing was overwritten, and the change must be rebased and checked
     * again (RFC 0007 §5.5). [current] is what the store holds.
     */
    class Conflicted(val current: SnapshotDigest?) : PublishOutcome

    /** The candidate removes policies other documents still refer to (RFC 0007 §5.8): nothing was written. */
    class Referenced(val referrers: Map<PolicyId, Set<SnapshotKey>>) : PublishOutcome
}

/** What [Revisions.verifyChain] found walking a head's `parent` chain. */
sealed interface ChainCheck {
    /** Every parent down to [oldest] has its record; older ones were reclaimed or never existed. */
    class Intact(val oldest: Long) : ChainCheck

    /** The record [missing] should exist and does not: a lost write (a failover) or a deleted key. */
    class Broken(val missing: ParentRevision) : ChainCheck
}

/** What [Revisions.reclaim] removed. */
class Reclaimed(val records: List<SnapshotDigest>, val orphans: List<SnapshotDigest>) {
    companion object {
        val NOTHING: Reclaimed = Reclaimed(emptyList(), emptyList())
    }
}

/** How long revision records are kept (RFC 0007 §5.7). */
class RetentionPolicy(
    /** The most recent revisions along the chain that are always kept (`cosec.control.revisions.max-count`). */
    val maxCount: Int = 50,
    /** Revisions younger than this are always kept (`cosec.control.revisions.min-age`). */
    val minAge: Duration = Duration.ofDays(7),
    /** Records not on the chain are removed once this old (`cosec.control.revisions.orphan-age`). */
    val orphanAge: Duration = Duration.ofHours(1),
    /** How long one instance holds the reclamation lease of a document. */
    val leaseDuration: Duration = Duration.ofMinutes(5),
) {
    init {
        require(maxCount >= 1) { "At least the head is kept." }
        require(
            !minAge.isNegative && !orphanAge.isNegative && !leaseDuration.isNegative
        ) { "Durations are not negative." }
    }
}

/**
 * Revisions of the platform, app and tenant documents over a [ControlStore] (RFC 0007 §5.4–§5.8).
 *
 * [publish] writes in the order that never loses content and never lets the data plane see anything but the old or
 * the new head:
 * 1. the record is registered as pending in the index, then written, create-only (`putIfMatch(expected = null)`);
 * 2. the back-references the candidate adds are added (the index stays a superset of the true references);
 * 3. the head is replaced with `putIfMatch(expected = base digest)`: one key, one atomic step (P7). A conflict leaves
 *    the head as someone else wrote it and is reported, never retried on the new content (RFC 0007 §5.5);
 * 4. the index gains the revision, and back-references only the base had are removed.
 *
 * A failure after step 3 loses no decision and no content: the index is rebuilt from the chain ([history]), extra
 * references block at most a deletion. The records of conflicted publishes are reclaimed as orphans ([reclaim]).
 */
class Revisions(
    private val store: ControlStore,
    private val clock: Clock = Clock.systemUTC(),
    val retention: RetentionPolicy = RetentionPolicy(),
    /** Attempts of a conditional replace of the index or the back-references before it gives up. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val holder: String = UUID.randomUUID().toString()

    /** The head of [key], read from the store of record. */
    suspend fun head(key: SnapshotKey): Head? {
        val stored = store.head(requireDocumentKey(key)) ?: return null
        return Head(key, decode(key, stored.document), stored)
    }

    /**
     * Publishes [candidate] over [base] (`null`: [key] has no document yet) for [publication]. The candidate's own
     * revision, time and provenance are replaced: the revision follows the base's, the parent is the base.
     */
    suspend fun publish(
        key: SnapshotKey,
        base: Head?,
        candidate: PolicyDocument,
        publication: Publication
    ): PublishOutcome {
        requireDocumentKey(key)
        require(base == null || base.key == key) { "The base of [$key] is a head of [$key], not of [${base?.key}]." }
        val (kind, owner) = ownerOf(key)
        require(candidate.kind == kind && candidate.owner == owner) { "The candidate is not a document of [$key]." }
        referenced(key, base, candidate)?.let { return it }
        val document = next(base, candidate, publication)
        val revision = document.revision
        val publishedAt = document.publishedAt
        val stored =
            SnapshotDocument(PolicyDocumentCodec.SCHEMA, revision, publishedAt, PolicyDocumentCodec.encode(document))
        val digest = store.digestOf(stored)
        updateIndex(key) { it.copy(pending = it.pending + PendingRecord(digest, publishedAt)) } ||
            warn("[$key] revision $revision: its record could not be registered as pending; an orphan would stay")
        check(store.putRecord(key, stored) == digest) { "The store addressed the record of [$key] by another digest." }
        val before = base?.let { references(it.document) }.orEmpty()
        val after = references(document)
        for ((target, policy) in after - before) {
            check(updateReferrers(target, policy) { it + key }) {
                "[$key]: the reference to [$policy] of [$target] could not be indexed; the head was not moved."
            }
        }
        when (val write = store.putHead(key, base?.digest, stored)) {
            is HeadWrite.Conflict -> {
                log.info { "[$key] changed since revision ${base?.revision ?: 0} was read; not published." }
                return PublishOutcome.Conflicted(write.current)
            }

            is HeadWrite.Written -> check(
                write.digest == digest
            ) { "The store wrote the head of [$key] as another digest." }
        }
        published(key, RevisionEntry(revision, digest, publishedAt), before - after)
        return PublishOutcome.Published(revision, digest)
    }

    /** The document [publish] writes: the candidate's body at the revision after [base], naming [base] as its parent. */
    private fun next(base: Head?, candidate: PolicyDocument, publication: Publication): PolicyDocument = PolicyDocument(
        kind = candidate.kind,
        owner = candidate.owner,
        revision = (base?.revision ?: 0) + 1,
        publishedAt = clock.instant(),
        policies = candidate.policies,
        bindings = candidate.bindings,
        catalog = candidate.catalog,
        limits = candidate.limits,
        attributes = candidate.attributes,
        rateLimits = candidate.rateLimits,
        provenance = Provenance(
            parent = base?.let { ParentRevision(it.revision, it.digest.toString()) },
            change = publication.change,
            kind = publication.kind,
            publishedBy = publication.publishedBy,
            approvedBy = publication.approvedBy,
            shadow = publication.shadow,
        ),
    )

    /** After the head moved to [entry] (§5.4 steps 5 and 6): index it, drop the [dropped] references. Failures only warn. */
    private suspend fun published(key: SnapshotKey, entry: RevisionEntry, dropped: Set<Pair<SnapshotKey, PolicyId>>) {
        updateIndex(key) { index ->
            index.copy(
                entries = index.entries.filter { it.revision < entry.revision } + entry,
                pending = index.pending.filter { it.digest != entry.digest },
            )
        } || warn("[$key] revision ${entry.revision} is published but not indexed; history() rebuilds it from the chain")
        for ((target, policy) in dropped) {
            updateReferrers(target, policy) { it - key } ||
                warn("[$key] no longer refers to [$policy] of [$target], which stays indexed")
        }
    }

    /**
     * The retained revisions of [key], oldest first. When the index is behind the head (a publish that failed after
     * its head write, or a lost index), the missing entries are found along the head's `parent` chain and added.
     */
    suspend fun history(key: SnapshotKey): List<RevisionEntry> {
        val head = head(key) ?: return store.index(key)?.value?.entries.orEmpty()
        val indexed = store.index(key)?.value ?: RevisionIndex.EMPTY
        val latest = indexed.latest?.revision ?: 0
        if (head.revision <= latest) {
            return indexed.entries
        }
        val missing = ArrayList<RevisionEntry>()
        var document: PolicyDocument? = head.document
        var digest: SnapshotDigest? = head.digest
        while (document != null && digest != null && document.revision > latest) {
            missing += RevisionEntry(document.revision, digest, document.publishedAt)
            val parent = document.provenance?.parent ?: break
            digest = SnapshotDigest(hex(parent.digest))
            document = store.record(key, digest)?.let { decode(key, it) }
        }
        val repaired = missing.sortedBy { it.revision }
        updateIndex(key) { index ->
            val known = index.entries.map { it.revision }.toSet()
            index.copy(
                entries = (index.entries + repaired.filter { it.revision !in known }).sortedBy { it.revision },
                pending = index.pending.filter { pending -> repaired.none { it.digest == pending.digest } },
            )
        } || warn("[$key]: the rebuilt history could not be written back to the index")
        return (indexed.entries.filter { it.revision < (repaired.firstOrNull()?.revision ?: Long.MAX_VALUE) } + repaired)
    }

    /** The document [key] had at [revision], if its record is retained: for rollback and for explaining by version. */
    suspend fun revision(key: SnapshotKey, revision: Long): PolicyDocument? {
        val entry = history(key).firstOrNull { it.revision == revision } ?: return null
        return store.record(key, entry.digest)?.let { decode(key, it) }
    }

    /**
     * The body to roll [key] back to [revision] (RFC 0007 §5.6): the policies, bindings and catalog of that revision.
     * It is published like any change, with [ProvenanceKind.ROLLBACK], and goes through every check of the current
     * documents; only the whole document is restored, never one change undone.
     */
    suspend fun rollbackCandidate(key: SnapshotKey, revision: Long): PolicyDocument =
        revision(key, revision) ?: throw IllegalArgumentException("[$key] has no retained revision $revision.")

    /**
     * Walks the `parent` chain of [key]'s head (`verify-chain`, RFC 0007 §5.4): every parent at or above the index
     * floor must have its record, holding that revision. A missing one is a lost write.
     */
    suspend fun verifyChain(key: SnapshotKey): ChainCheck {
        val head = head(key) ?: return ChainCheck.Intact(0)
        val floor = store.index(key)?.value?.floor ?: 1
        if (store.record(key, head.digest) == null) {
            return ChainCheck.Broken(ParentRevision(head.revision, head.digest.toString()))
        }
        var document = head.document
        while (true) {
            val parent = document.provenance?.parent ?: return ChainCheck.Intact(document.revision)
            val record = store.record(key, SnapshotDigest(hex(parent.digest)))
            if (record == null) {
                return if (parent.revision >= floor) ChainCheck.Broken(parent) else ChainCheck.Intact(document.revision)
            }
            document = decode(key, record)
            if (document.revision != parent.revision) {
                return ChainCheck.Broken(parent)
            }
        }
    }

    /**
     * Removes the records of [key] that the [retention] no longer keeps (RFC 0007 §5.7): beyond the [RetentionPolicy.maxCount]
     * most recent revisions and older than [RetentionPolicy.minAge], unless [keep] names them (the bases and rollback
     * targets of changes still open), and the orphans older than [RetentionPolicy.orphanAge]. One instance at a time:
     * without the lease it removes nothing.
     */
    suspend fun reclaim(key: SnapshotKey, keep: Set<SnapshotDigest> = emptySet()): Reclaimed {
        if (!store.acquireLease("reclaim:$key", holder, retention.leaseDuration)) {
            return Reclaimed.NOTHING
        }
        val entries = history(key)
        val now = clock.instant()
        val recent = entries.takeLast(retention.maxCount).map { it.digest }.toSet()
        val head = head(key)?.digest
        val removed = entries.filter { entry ->
            entry.digest !in recent && entry.digest !in keep && entry.digest != head &&
                entry.publishedAt.isBefore(now.minus(retention.minAge))
        }
        var orphans: List<SnapshotDigest> = emptyList()
        val updated = updateIndex(key) { index ->
            val chain = index.entries.map { it.digest }.toSet()
            orphans = index.pending.filter { pending ->
                pending.digest !in chain && pending.digest !in keep && pending.digest != head &&
                    pending.since.isBefore(now.minus(retention.orphanAge))
            }.map { it.digest }
            index.copy(
                entries = index.entries.filter { entry -> removed.none { it.digest == entry.digest } },
                pending = index.pending.filter { it.digest !in orphans },
                floor = maxOf(index.floor, (removed.maxOfOrNull { it.revision } ?: 0) + 1),
            )
        }
        if (!updated) {
            warn("[$key]: the index kept changing; nothing was reclaimed")
            return Reclaimed.NOTHING
        }
        // The index no longer lists them, so a failure here leaves unlisted records behind, never a listed one missing.
        (removed.map { it.digest } + orphans).forEach { store.removeRecord(key, it) }
        return Reclaimed(removed.map { it.digest }, orphans)
    }

    /** The documents indexed as referring to [policy] of [target] (RFC 0007 §5.8). */
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> =
        store.referrers(target, policy)?.value.orEmpty()

    /**
     * Adds whatever the head of [key] refers to and the back-reference index misses (`repair-references`, RFC 0007
     * §5.8). It only adds: an extra entry blocks a deletion at most, a missing one would let it through.
     */
    suspend fun repairReferences(key: SnapshotKey) {
        val head = head(key) ?: return
        for ((target, policy) in references(head.document)) {
            check(
                updateReferrers(target, policy) { it + key }
            ) { "[$key]: the reference to [$policy] of [$target] was not repaired." }
        }
    }

    /** The removed policies of a platform or app candidate that other documents still refer to; `null` for none. */
    private suspend fun referenced(
        key: SnapshotKey,
        base: Head?,
        candidate: PolicyDocument
    ): PublishOutcome.Referenced? {
        if (base == null || key is SnapshotKey.Tenant) {
            return null
        }
        val kept = candidate.policies.map { it.id }.toSet()
        val blocked = base.document.policies.map { it.id }.filter { it !in kept }
            .associateWith { referrers(key, it) - key }
            .filterValues { it.isNotEmpty() }
        return if (blocked.isEmpty()) null else PublishOutcome.Referenced(blocked)
    }

    /**
     * What [document] refers to in other documents: a binding to a policy it does not define names a platform
     * policy, and in a tenant document bound in one app, possibly that app's permission policy (RFC 0007 §3.1 N1).
     * Both are indexed: the index may hold more than the true references, never less.
     */
    private fun references(document: PolicyDocument): Set<Pair<SnapshotKey, PolicyId>> {
        if (document.kind == DocumentKind.PLATFORM) {
            return emptySet()
        }
        val own = document.policies.map { it.id }.toSet()
        val references = LinkedHashSet<Pair<SnapshotKey, PolicyId>>()
        for (binding in document.bindings) {
            if (binding.policy in own) continue
            references += SnapshotKey.Platform to binding.policy
            val app = binding.scope.app
            if (document.kind == DocumentKind.TENANT && app is Match.Exactly) {
                references += SnapshotKey.App(app.value.value) to binding.policy
            }
        }
        return references
    }

    private suspend fun updateIndex(key: SnapshotKey, change: (RevisionIndex) -> RevisionIndex): Boolean {
        repeat(maxAttempts) {
            val current = store.index(key)
            if (store.putIndex(key, current?.digest, change(current?.value ?: RevisionIndex.EMPTY))) {
                return true
            }
        }
        return false
    }

    private suspend fun updateReferrers(target: SnapshotKey, policy: PolicyId, change: (Set<SnapshotKey>) -> Set<SnapshotKey>): Boolean {
        repeat(maxAttempts) {
            val current = store.referrers(target, policy)
            val next = change(current?.value.orEmpty())
            if (current != null && next == current.value) {
                return true
            }
            if (store.putReferrers(target, policy, current?.digest, next)) {
                return true
            }
        }
        return false
    }

    private fun decode(key: SnapshotKey, document: SnapshotDocument): PolicyDocument {
        val (kind, owner) = ownerOf(key)
        return PolicyDocumentCodec.decode(kind, owner, document.bodyBytes())
    }

    private fun warn(message: String): Boolean {
        log.warn { message }
        return false
    }

    companion object {
        private val log = KotlinLogging.logger {}
        const val DEFAULT_MAX_ATTEMPTS: Int = 16

        /** The document kind and owner of [key]: the platform, an app or a tenant document. */
        fun ownerOf(key: SnapshotKey): Pair<DocumentKind, String> = when (key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM to ""
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
            SnapshotKey.Policies -> throw IllegalArgumentException(
                "The control plane publishes platform, app and tenant documents."
            )
        }

        private fun requireDocumentKey(key: SnapshotKey): SnapshotKey = key.also { ownerOf(it) }

        private fun hex(text: String): ByteArray = ByteArray(
            text.length / 2
        ) { text.substring(2 * it, 2 * it + 2).toInt(HEX).toByte() }

        private const val HEX = 16
    }
}
