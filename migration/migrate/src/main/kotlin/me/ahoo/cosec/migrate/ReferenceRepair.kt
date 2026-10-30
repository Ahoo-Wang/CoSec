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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * What [ReferenceRepair.run] did: the documents it visited, the references it added, the documents it could not, and
 * how many apps the app list names now, `null` when it could not be completed.
 */
class RepairResult(val documents: Int, val added: Int, val failed: Map<SnapshotKey, String>, val apps: Int?)

/**
 * `repair-references` (RFC 0007 §5.8): adds to the back-reference index whatever the head of each document refers to
 * and the index misses. After the upgrade from 8.0.0-alpha.2 that is the tenant documents' own policy ids, which the
 * index records since RC1-9, so that the platform cannot add a policy of an id a tenant already uses.
 *
 * It also lists every app document the store lists among the apps and marks the app list complete (RC1-4): the grant
 * analysis trusts only a complete list, so until this has run after the upgrade every grant over every app waits for
 * approval. The list it completes is as good as [ControlStore.documents]: never fewer than exist, as long as that list
 * has not been lost.
 *
 * It only adds, never removes, so it may run while the control plane runs, and run again: a second run adds nothing.
 * The index stays what it must be, a superset of the true references. A reference added for a head that a publish has
 * replaced meanwhile is an extra one, which blocks a deletion at most; it never hides a reference, so it never lets a
 * deletion through. Every document [ControlStore.documents] lists is visited; one whose head cannot be read, or whose
 * references cannot be written, is reported in [RepairResult.failed], never skipped silently.
 */
class ReferenceRepair(private val store: ControlStore) {
    private val revisions = Revisions(store)

    @Suppress("TooGenericExceptionCaught")
    fun run(): RepairResult = runBlocking {
        var documents = 0
        var added = 0
        val failed = LinkedHashMap<SnapshotKey, String>()
        val listed = store.documents().sortedBy { it.toString() }
        for (key in listed) {
            documents++
            try {
                added += revisions.repairReferences(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[key] = e.message ?: e.javaClass.name
            }
        }
        val apps = listed.filterIsInstance<SnapshotKey.App>().mapTo(HashSet()) { it.appId }
        val completed = revisions.completeApps(apps)
        RepairResult(documents, added, failed, if (completed) store.apps()?.value?.entries?.size else null)
    }
}
