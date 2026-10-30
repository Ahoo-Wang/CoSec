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

import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A [ControlStore] in memory, for tests and single-instance development. Digests are the SHA-256 of a document's
 * bytes and, for the indexes, of their rendering, cut to the 20 bytes the document format's `parent.digest` holds
 * (CoCache's are SHA-1, RFC 0003 §4.1). They address content; they are not a security control. Every write is atomic
 * under one lock.
 */
class InMemoryControlStore(private val clock: Clock = Clock.systemUTC()) : ControlStore {
    private val lock = Any()
    private val heads = HashMap<SnapshotKey, SnapshotDocument>()
    private val records = HashMap<Pair<SnapshotKey, SnapshotDigest>, SnapshotDocument>()
    private val indexes = HashMap<SnapshotKey, RevisionIndex>()
    private val references = HashMap<Pair<SnapshotKey, PolicyId>, Set<SnapshotKey>>()
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

    override suspend fun openChanges(): Set<ChangeId> = synchronized(lock) {
        changes.filterValues { (change, _) -> !change.state.terminal }.keys.toSet()
    }

    override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Versioned<Set<SnapshotKey>>? =
        synchronized(lock) { references[target to policy]?.let { Versioned(it, setDigest(it)) } }

    override suspend fun putReferrers(
        target: SnapshotKey,
        policy: PolicyId,
        expected: SnapshotDigest?,
        referrers: Set<SnapshotKey>,
    ): Boolean = synchronized(lock) {
        val current = references[target to policy]?.let(::setDigest)
        if (current != expected) {
            return false
        }
        if (referrers.isEmpty()) references.remove(target to policy) else references[target to policy] = referrers
        true
    }

    override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? =
        synchronized(lock) { changes[id]?.let { (change, version) -> Versioned(change, version(version)) } }

    override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest): Boolean = synchronized(lock) {
        if (changes[change.id]?.let { version(it.second) } != expected) {
            return false
        }
        changes[change.id] = change to ++writes
        true
    }

    override suspend fun candidate(key: SnapshotKey): StoredCandidate? = synchronized(lock) { candidates[key] }

    override suspend fun putCandidate(key: SnapshotKey, candidate: StoredCandidate?) {
        synchronized(lock) {
            if (candidate == null) candidates.remove(key) else candidates[key] = candidate
        }
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

    private fun setDigest(set: Set<SnapshotKey>): SnapshotDigest =
        digest(set.map { it.toString() }.sorted().joinToString("\n").encodeToByteArray())

    private fun digest(bytes: ByteArray): SnapshotDigest =
        SnapshotDigest(MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(DIGEST_BYTES))

    private companion object {
        const val DIGEST_BYTES = 20
    }
}
