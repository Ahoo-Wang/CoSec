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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ReferrerHeads
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * `repair-references` (RFC 0007 §5.8): brings the back-reference index in line with what the heads refer to, and converts
 * a store written before 8.0.0-beta.5 into the sets of 8.0.0-beta.5 ([conversion], `MIG-4`).
 *
 * In this order, under the lease [LEASE], so that two runs never convert the same value at once:
 *
 * 1. the old document list, if there is one, is copied into the document set (members added only);
 * 2. every back-reference value a head touches ([Revisions.referenceKeys]) that is in the old format becomes a set. A
 *    set is never replaced: only a value CoCache reads in the old format is, which no publish can add to meanwhile
 *    (`CTL-42`). A value no head touches stays in the old format: it blocks nothing, and a later reference to it fails
 *    closed, naming this command, until it runs again;
 * 3. whatever the head of each document refers to and the index misses is added, with the flag of each entry (RC1-9,
 *    `CTL-43`): entries are only ever added here;
 * 4. the app list is completed (RC1-4): the grant analysis trusts only a complete list;
 * 5. what the index has and no head does, with the same flag, is removed ([Revisions.pruneReferences]): what a publish
 *    indexed before a head write it lost, and the converted entries a head now defines itself;
 * 6. only when nothing failed, the old document list is removed: from then on the store is in the new format, and the
 *    starter no longer reports it as unrepaired.
 *
 * It may run while the control plane of 8.0.0-beta.5 runs, and run again: a second run changes nothing. The control
 * planes of earlier versions must be stopped first: they write the old format. Every document [ControlStore.documents]
 * lists is visited; one whose head cannot be read, or whose references cannot be written, is reported in
 * [RepairResult.failed], never skipped silently.
 */
class ReferenceRepair(private val store: ControlStore, private val conversion: StoreConversion? = null) {
    private val revisions = Revisions(store)

    fun run(): RepairResult = runBlocking {
        val holder = UUID.randomUUID().toString()
        if (!store.acquireLease(LEASE, holder, LEASE_DURATION)) {
            return@runBlocking RepairResult(0, 0, 0, emptyMap(), null, busy = true)
        }
        try {
            repair()
        } finally {
            withContext(NonCancellable) { store.releaseLease(LEASE, holder) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun repair(): RepairResult {
        val failed = LinkedHashMap<SnapshotKey, String>()
        val legacyDocuments = conversion?.legacyDocuments()
        legacyDocuments?.let { conversion?.listDocuments(it) }
        val listed = store.documents().sortedBy { it.toString() }
        val converted = conversion?.let { convertAll(it, listed, failed) } ?: 0
        var documents = 0
        var added = 0
        for (key in listed) {
            documents++
            try {
                added += revisions.repairReferences(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.putIfAbsent(key, e.message ?: e.javaClass.name)
            }
        }
        var removed = 0
        val heads = ReferrerHeads()
        // Only the platform's and the apps' policies are referred to from other documents (RFC 0007 §5.8).
        for (key in listed.filterNot { it is SnapshotKey.Tenant }) {
            try {
                removed += revisions.pruneReferences(key, heads)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.putIfAbsent(key, e.message ?: e.javaClass.name)
            }
        }
        val apps = listed.filterIsInstance<SnapshotKey.App>().mapTo(HashSet()) { it.appId }
        val completed = revisions.completeApps(apps)
        val converting = legacyDocuments != null
        val formatDone = converting && failed.isEmpty() && completed
        if (formatDone) {
            conversion?.removeLegacyDocuments()
        }
        return RepairResult(
            documents,
            added,
            removed,
            failed,
            if (completed) store.apps()?.value?.entries?.size else null,
            converted = converted,
            converting = converting,
            formatDone = formatDone,
        )
    }

    /** Step 2: the old-format values the heads touch, each converted once; the number converted. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun convertAll(
        conversion: StoreConversion,
        listed: List<SnapshotKey>,
        failed: MutableMap<SnapshotKey, String>,
    ): Int {
        val seen = HashSet<Pair<SnapshotKey, PolicyId>>()
        var converted = 0
        for (key in listed) {
            try {
                for (reference in revisions.referenceKeys(key)) {
                    if (seen.add(reference) && conversion.convert(reference.first, reference.second)) {
                        converted++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.putIfAbsent(key, e.message ?: e.javaClass.name)
            }
        }
        return converted
    }

    companion object {
        /** The lease two runs of the repair share, so that they never convert the same value at once. */
        const val LEASE = "repair-references"

        /** Longer than a run over the largest store takes; a run that stops early releases it. */
        val LEASE_DURATION: Duration = Duration.ofHours(1)
    }
}
