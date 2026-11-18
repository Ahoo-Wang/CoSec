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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * The app list of [Revisions] (RC1-4): the app documents there are, complete once it names every one. It is marked
 * complete on a new store by the platform's first revision, incomplete again when an app document it does not name
 * appears, and complete by `repair-references`.
 */
internal class AppListing(
    private val store: ControlStore,
    private val writes: RevisionWrites,
    private val maxAttempts: Int,
) {
    /**
     * Marks the app list complete once the platform's first revision is written (RC1-4): on a new store no app document
     * exists before it. Only when the list names every app document [ControlStore.documents] lists that has a head, as
     * `repair-references` leaves it: a platform head that was lost and is published again may find app documents a
     * rebuilt list does not name. A listed app without a head is one whose first publish stopped before its head
     * write; it does not count, since an app lists itself before its head is written. The head is written whatever this
     * does, so a failure is only logged, a store's own timeout among them, as in [settle].
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun completeOnNewStore() {
        try {
            val documents = store.documents()
            repeat(maxAttempts) {
                val current = store.apps()
                val list = current?.value ?: AppList.EMPTY
                val missing = missingApps(documents, list.entries)
                if (missing.isNotEmpty()) {
                    warn(
                        "The platform's first revision found app documents $missing the app list does not name; it " +
                            "stays incomplete until cosec-migrate repair-references runs"
                    )
                    return
                }
                if (list.complete || store.putApps(current?.digest, list.copy(complete = true))) {
                    return
                }
            }
            warn("The app list kept changing and was not marked complete; grants over every app wait for approval")
        } catch (e: Exception) {
            log.warn(e) { "The app list could not be marked complete; grants over every app wait for approval." }
        }
    }

    /**
     * Marks a complete app list incomplete again when [documents] names an app document with a head it does not (RC1-4):
     * one published by an instance or a cosec-migrate that does not keep the list, during a rolling upgrade or after a
     * downgrade. The apps it misses; empty when it misses none, is not complete, or is not there.
     */
    suspend fun reconcileApps(documents: Set<SnapshotKey>): Set<String> {
        var marked: Set<String>? = null
        repeat(maxAttempts) {
            val current = store.apps() ?: return emptySet()
            if (!current.value.complete) {
                // A write whose reply was lost reads back as a conflict: what the earlier attempt found is still so.
                marked?.let { incompleteAgain(it) }
                return marked.orEmpty()
            }
            val missing = missingApps(documents, current.value.entries)
            if (missing.isEmpty()) {
                return emptySet()
            }
            marked = missing
            if (store.putApps(current.digest, current.value.copy(complete = false))) {
                incompleteAgain(missing)
                return missing
            }
        }
        throw IllegalStateException("The app list kept changing; gave up after $maxAttempts attempts.")
    }

    private fun incompleteAgain(missing: Set<String>) {
        warn(
            "The app list misses the app documents $missing; it is incomplete again, and grants over every app wait " +
                "for approval until cosec-migrate repair-references runs"
        )
    }

    /**
     * The apps of [documents] that [listed] does not name and that have a head; one whose head cannot be read counts,
     * since it may well have one.
     */
    private suspend fun missingApps(documents: Set<SnapshotKey>, listed: Set<String>): Set<String> =
        documents.filterIsInstance<SnapshotKey.App>()
            .filter { it.appId !in listed && hasHead(it) }
            .mapTo(HashSet()) { it.appId }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun hasHead(key: SnapshotKey.App): Boolean = try {
        store.head(key) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The head of [$key] could not be read; it counts as an app document the app list misses." }
        true
    }

    /**
     * Lists [apps] among the app documents and marks the list complete (`repair-references`, RC1-4): the caller has
     * listed every app document there is. `false` when the list kept changing.
     */
    suspend fun completeApps(
        apps: Set<String>
    ): Boolean = writes.updateApps { AppList(it.entries + apps, complete = true) }
}
