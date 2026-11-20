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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.document.shadow.ShadowCodec
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A [ControlStore] in memory, for tests and single-instance development. Digests are the SHA-256 of a document's
 * bytes and, for the indexes, of their rendering, as CoCache's are (RFC 0003 §4.1). They address content; they are not a
 * security control. Every write is atomic under one lock.
 */
// stays: one method per store operation of ControlStore
@Suppress("TooManyFunctions")
@ExperimentalCoSecApi
class InMemoryControlStore(private val clock: Clock = Clock.systemUTC()) : ControlStore {
    private val lock = Any()
    private val heads = HashMap<SnapshotKey, SnapshotDocument>()
    private val records = HashMap<Pair<SnapshotKey, SnapshotDigest>, SnapshotDocument>()
    private val indexes = HashMap<SnapshotKey, RevisionIndex>()
    private val references = HashMap<Pair<SnapshotKey, PolicyId>, MutableSet<ReferrerEntry>>()
    private val leases = HashMap<String, Pair<String, Instant>>()
    private val changes = HashMap<ChangeId, Pair<ChangeRequest, Long>>()
    private val stages = HashMap<SnapshotKey, Pair<StageSlot, Long>>()
    private val candidates = HashMap<SnapshotKey, StoredCandidate>()
    private var apps: AppList? = null
    private var writes = 0L

    override fun digestOf(document: SnapshotDocument): SnapshotDigest = digest(document.bodyBytes())

    override suspend fun head(key: SnapshotKey): StoredDocument? =
        synchronized(lock) { heads[key]?.let { StoredDocument(it, digestOf(it)) } }

    override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument): HeadWrite =
        synchronized(lock) {
            val current = heads[key]?.let(::digestOf)
            if (current != expected) {
                return HeadWrite.Conflict(current)
            }
            heads[key] = document
            HeadWrite.Written(digestOf(document))
        }

    override suspend fun record(key: SnapshotKey, digest: SnapshotDigest): SnapshotDocument? =
        synchronized(lock) { records[key to digest] }

    override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest = synchronized(lock) {
        val digest = digestOf(document)
        records.putIfAbsent(key to digest, document)
        digest
    }

    override suspend fun removeRecord(key: SnapshotKey, digest: SnapshotDigest) {
        synchronized(lock) { records.remove(key to digest) }
    }

    override suspend fun index(key: SnapshotKey): Versioned<RevisionIndex>? =
        synchronized(lock) { indexes[key]?.let { Versioned(it, digest(it.toString().encodeToByteArray())) } }

    override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean =
        synchronized(lock) {
            val current = indexes[key]?.let { digest(it.toString().encodeToByteArray()) }
            if (current != expected) {
                return false
            }
            indexes[key] = index
            true
        }

    override suspend fun documents(): Set<SnapshotKey> = synchronized(lock) { indexes.keys.toSet() }

    override suspend fun apps(): Versioned<AppList>? =
        synchronized(lock) { apps?.let { Versioned(it, appsDigest(it)) } }

    override suspend fun putApps(expected: SnapshotDigest?, apps: AppList): Boolean = synchronized(lock) {
        if (this.apps?.let(::appsDigest) != expected) {
            return false
        }
        this.apps = apps
        true
    }

    /** Test access: removes the app list, as a lost key would. */
    fun dropApps() {
        synchronized(lock) { apps = null }
    }

    /** Test access: a list of the format before 8.0.0-beta.5 is (still) there, so the open-change sets are not kept. */
    @Volatile
    var legacyOpenList: Boolean = false

    private var openChangesKept = false

    override suspend fun openChangesKept(): Boolean = synchronized(lock) { openChangesKept && !legacyOpenList }

    override suspend fun keepOpenChanges() {
        synchronized(lock) { if (!legacyOpenList) openChangesKept = true }
    }

    override suspend fun openChanges(document: SnapshotKey): List<ListedChange> = synchronized(lock) {
        open().filter { it.key == document }.map(ListedChange::Readable)
    }

    override suspend fun changes(
        state: ChangeState,
        after: String?,
        limit: Int,
        documents: (SnapshotKey) -> Boolean,
    ): ChangePage = synchronized(lock) {
        val listed = open().filter { it.state == state && documents(it.key) }.sortedBy(
            ::cursorOf
        ).filter { after == null || cursorOf(it) > after }
        val page = listed.take(limit)
        ChangePage(page.map(ListedChange::Readable), if (listed.size > limit) cursorOf(page.last()) else null)
    }

    private fun open(): List<ChangeRequest> = changes.values.map { (change, _) -> change }.filterNot { it.state.terminal }

    private fun cursorOf(change: ChangeRequest): String = "${change.key}#${change.id.value}"

    override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry> =
        synchronized(lock) { references[target to policy]?.toSet().orEmpty() }

    override suspend fun addReferrer(target: SnapshotKey, policy: PolicyId, entry: ReferrerEntry) {
        synchronized(lock) { references.getOrPut(target to policy) { LinkedHashSet() } += entry }
    }

    override suspend fun removeReferrers(target: SnapshotKey, policy: PolicyId, entries: Set<ReferrerEntry>) {
        synchronized(lock) {
            val current = references[target to policy] ?: return
            current -= entries
            if (current.isEmpty()) references -= target to policy
        }
    }

    override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? =
        synchronized(lock) { changes[id]?.let { (change, version) -> Versioned(change, version(version)) } }

    override suspend fun putChange(
        expected: SnapshotDigest?,
        change: ChangeRequest,
        previous: ChangeState?,
    ): Boolean = synchronized(lock) {
        if (changes[change.id]?.let { version(it.second) } != expected) {
            return false
        }
        changes[change.id] = change to ++writes
        true
    }

    override suspend fun candidate(key: SnapshotKey): StoredCandidate? = synchronized(lock) { candidates[key] }

    override suspend fun stagedCandidate(key: SnapshotKey): Versioned<StoredCandidate>? =
        synchronized(lock) { candidates[key]?.let { Versioned(it, candidateDigest(it)) } }

    override suspend fun putCandidate(key: SnapshotKey, expected: SnapshotDigest?, candidate: StoredCandidate): Boolean =
        synchronized(lock) {
            if (candidates[key]?.let(::candidateDigest) != expected) {
                return false
            }
            candidates[key] = candidate
            true
        }

    override suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean = synchronized(lock) {
        candidates[key]?.window == window && candidates.remove(key) != null
    }

    /** The staged list's members; [staged] answers them once [keepStagedList] made the list kept. */
    private val listed = HashSet<Pair<SnapshotKey, String>>()
    private var kept = false

    override suspend fun listStaged(key: SnapshotKey, window: String) {
        synchronized(lock) { listed += key to window }
    }

    override suspend fun unlistStaged(key: SnapshotKey, window: String) {
        synchronized(lock) { listed -= key to window }
    }

    override suspend fun keepStagedList() {
        synchronized(lock) { kept = true }
    }

    override suspend fun staged(): StagedCandidates? = synchronized(lock) {
        if (kept) StagedCandidates(listed.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }) else null
    }

    override suspend fun stage(key: SnapshotKey): Versioned<StageSlot>? =
        synchronized(lock) { stages[key]?.let { (slot, version) -> Versioned(slot, version(version)) } }

    override suspend fun putStage(key: SnapshotKey, expected: SnapshotDigest?, slot: StageSlot): Boolean =
        synchronized(lock) {
            if (stages[key]?.let { version(it.second) } != expected) {
                return false
            }
            stages[key] = slot to ++writes
            true
        }

    /** A version as a digest: every write of a change or slot gets a new one. */
    private fun version(version: Long): SnapshotDigest = digest(version.toString().encodeToByteArray())

    override suspend fun acquireLease(name: String, holder: String, duration: Duration): Boolean = synchronized(lock) {
        val now = clock.instant()
        val current = leases[name]
        if (current != null && current.first != holder && current.second.isAfter(now)) {
            return false
        }
        leases[name] = holder to now.plus(duration)
        true
    }

    override suspend fun releaseLease(name: String, holder: String) {
        synchronized(lock) {
            if (leases[name]?.first == holder) {
                leases.remove(name)
            }
        }
    }

    /** Test access: the record keys of [key], to assert what was written and reclaimed. */
    fun recordDigests(key: SnapshotKey): Set<SnapshotDigest> =
        synchronized(lock) { records.keys.filter { it.first == key }.mapTo(HashSet()) { it.second } }

    /** Test access: removes the index of [key], as a lost index key would. */
    fun dropIndex(key: SnapshotKey) {
        synchronized(lock) { indexes.remove(key) }
    }

    /** Each entry length-prefixed, since an entry may hold any character. */
    private fun appsDigest(apps: AppList): SnapshotDigest {
        val entries = apps.entries.sorted().joinToString("") { "${it.length}:$it" }
        return digest("$entries|${apps.complete}".encodeToByteArray())
    }

    private fun candidateDigest(candidate: StoredCandidate): SnapshotDigest = digest(ShadowCodec.encode(candidate))

    private fun digest(bytes: ByteArray): SnapshotDigest =
        SnapshotDigest(SnapshotDigest.SHA_256, MessageDigest.getInstance(SnapshotDigest.SHA_256).digest(bytes))
}
