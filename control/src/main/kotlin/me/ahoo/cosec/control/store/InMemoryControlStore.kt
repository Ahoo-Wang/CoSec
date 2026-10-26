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

import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A [ControlStore] in memory, for tests and single-instance development. Digests are the SHA-1 of a document's bytes
 * and, for the indexes, of their rendering; every write is atomic under one lock.
 */
class InMemoryControlStore(private val clock: Clock = Clock.systemUTC()) : ControlStore {
    private val lock = Any()
    private val heads = HashMap<SnapshotKey, SnapshotDocument>()
    private val records = HashMap<Pair<SnapshotKey, SnapshotDigest>, SnapshotDocument>()
    private val indexes = HashMap<SnapshotKey, RevisionIndex>()
    private val references = HashMap<Pair<SnapshotKey, PolicyId>, Set<SnapshotKey>>()
    private val leases = HashMap<String, Pair<String, Instant>>()

    override fun digestOf(document: SnapshotDocument): SnapshotDigest = sha1(document.bodyBytes())

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
        synchronized(lock) { indexes[key]?.let { Versioned(it, sha1(it.toString().encodeToByteArray())) } }

    override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean =
        synchronized(lock) {
            val current = indexes[key]?.let { sha1(it.toString().encodeToByteArray()) }
            if (current != expected) {
                return false
            }
            indexes[key] = index
            true
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

    override suspend fun acquireLease(name: String, holder: String, duration: Duration): Boolean = synchronized(lock) {
        val now = clock.instant()
        val current = leases[name]
        if (current != null && current.first != holder && current.second.isAfter(now)) {
            return false
        }
        leases[name] = holder to now.plus(duration)
        true
    }

    /** Test access: the record keys of [key], to assert what was written and reclaimed. */
    fun recordDigests(key: SnapshotKey): Set<SnapshotDigest> =
        synchronized(lock) { records.keys.filter { it.first == key }.mapTo(HashSet()) { it.second } }

    /** Test access: removes the index of [key], as a lost index key would. */
    fun dropIndex(key: SnapshotKey) {
        synchronized(lock) { indexes.remove(key) }
    }

    private fun setDigest(set: Set<SnapshotKey>): SnapshotDigest =
        sha1(set.map { it.toString() }.sorted().joinToString("\n").encodeToByteArray())

    private fun sha1(bytes: ByteArray): SnapshotDigest = SnapshotDigest(
        MessageDigest.getInstance("SHA-1").digest(bytes)
    )
}
