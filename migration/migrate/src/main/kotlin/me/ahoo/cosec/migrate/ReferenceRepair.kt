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
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.time.Instant
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
class ReferenceRepair(
    private val store: ControlStore,
    private val conversion: StoreConversion? = null,
    /** Told each failure as it happens and a progress line every [PROGRESS_EVERY] documents (R2C-20). */
    private val progress: (String) -> Unit = {},
    /** How many document steps pass between two renewals of the lease and two progress lines. */
    private val progressEvery: Int = PROGRESS_EVERY,
) {
    private val revisions = Revisions(store)
    private val holder = "${UUID.randomUUID()} since ${Instant.now()}"
    private var visited = 0

    /** The policy ids each referrer's head defines, read once per run (R2C-29). */
    private val ownIds = HashMap<SnapshotKey, Set<PolicyId>>()

    fun run(): RepairResult = runBlocking {
        if (!store.acquireLease(LEASE, holder, LEASE_DURATION)) {
            return@runBlocking RepairResult(
                0,
                0,
                0,
                emptyMap(),
                null,
                busy = true,
                holder = conversion?.leaseHolder(LEASE)
            )
        }
        try {
            repair()
        } finally {
            withContext(NonCancellable) { store.releaseLease(LEASE, holder) }
        }
    }

    private suspend fun repair(): RepairResult {
        val failed = LinkedHashMap<SnapshotKey, String>()
        val legacyDocuments = conversion?.legacyDocuments()
        legacyDocuments?.let { conversion?.listDocuments(it) }
        val listed = store.documents().sortedBy { it.toString() }
        var converted = 0
        if (conversion != null) {
            val seen = HashSet<Pair<SnapshotKey, PolicyId>>()
            for (key in listed) {
                converted += each("convert", listed.size, key, failed) {
                    revisions.referenceKeys(key).count { convert(it, seen) }
                }
            }
        }
        val added = listed.sumOf { key -> each("add", listed.size, key, failed) { revisions.repairReferences(key) } }
        val heads = ReferrerHeads()
        // Only the platform's and the apps' policies are referred to from other documents (RFC 0007 §5.8).
        val referred = listed.filterNot { it is SnapshotKey.Tenant }
        val removed = referred.sumOf { key ->
            each("prune", referred.size, key, failed) {
                revisions.pruneReferences(key, heads)
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
            listed.size,
            added,
            removed,
            failed,
            if (completed) store.apps()?.value?.entries?.size else null,
            converted = converted,
            converting = converting,
            formatDone = formatDone,
        )
    }

    /** Step 2 for one value: converted once, if the heads touch it and it is in the old format. */
    private suspend fun convert(reference: Pair<SnapshotKey, PolicyId>, seen: MutableSet<Pair<SnapshotKey, PolicyId>>) =
        seen.add(reference) && checkNotNull(conversion).convert(reference.first, reference.second) { referrer ->
            reference.first is SnapshotKey.Platform && reference.second in ownIdsOf(referrer)
        }

    /** What [referrer]'s head defines itself, read once per run; nothing for a document that is not a tenant's. */
    private suspend fun ownIdsOf(referrer: SnapshotKey): Set<PolicyId> = ownIds[referrer] ?: run {
        val ids = if (referrer is SnapshotKey.Tenant) {
            revisions.head(referrer)?.document?.let { RoleExpansion.effective(it).policies.mapTo(HashSet()) { p -> p.id } }
        } else {
            null
        }
        ids.orEmpty().also { ownIds[referrer] = it }
    }

    /**
     * [step] for [key], one of [of] in [phase]; what it counted, or 0 when it failed, which [failed] names, keeping a
     * document's first failure. Every [PROGRESS_EVERY] documents it renews the lease, which another run must not take
     * while this one runs (R2C-8), and says how far it got.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun each(
        phase: String,
        of: Int,
        key: SnapshotKey,
        failed: MutableMap<SnapshotKey, String>,
        step: suspend () -> Int,
    ): Int {
        if (++visited % progressEvery == 0) {
            check(
                store.acquireLease(LEASE, holder, LEASE_DURATION)
            ) { "Another run took the lease [$LEASE]; this one stops." }
            progress("$phase: at $key, $visited document step(s) done ($of document(s) in this phase)")
        }
        return try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.name
            if (failed.putIfAbsent(key, message) == null) progress("FAILED $key: $message")
            0
        }
    }

    companion object {
        /** The lease two runs of the repair share, so that they never convert the same value at once. */
        const val LEASE = "repair-references"

        /**
         * How long the lease outlives its holder's last renewal: a run renews it every [PROGRESS_EVERY] documents, which
         * takes far less, so a run that died leaves it for at most this long.
         */
        val LEASE_DURATION: Duration = Duration.ofMinutes(10)

        /** How many document steps pass between two renewals of the lease and two progress lines. */
        const val PROGRESS_EVERY = 1_000
    }
}
