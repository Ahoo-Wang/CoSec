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

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText

/**
 * The sets of record of [CoCacheControlStore]: the back-references, one set per policy ([ReferrerMemberCodec]), and the
 * document keys, one set. Each is written a member at a time (`CTL-44`), read from Redis, and fails closed: a failure,
 * of a value of the format before 8.0.0-beta.5 names the command that converts it, and no other failure does (`CTL-42`,
 * R2C-7).
 */
internal class ControlSets(
    private val references: SetStore<ReferenceKey, ReferrerEntry>,
    private val documents: SetStore<String, String>,
) {
    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry> =
        failClosed(referencesOf(target, policy)) { references.suspending().get(ReferenceKey(target, policy)) }

    suspend fun add(target: SnapshotKey, policy: PolicyId, entry: ReferrerEntry) =
        failClosed(
            referencesOf(target, policy)
        ) { references.suspending().add(ReferenceKey(target, policy), listOf(entry)) }

    suspend fun remove(target: SnapshotKey, policy: PolicyId, entries: Set<ReferrerEntry>) {
        if (entries.isNotEmpty()) {
            failClosed(
                referencesOf(target, policy)
            ) { references.suspending().remove(ReferenceKey(target, policy), entries) }
        }
    }

    /** Replaces the set: only for the conversion of a value of the old format (`MIG-4`). */
    suspend fun replace(target: SnapshotKey, policy: PolicyId, entries: Set<ReferrerEntry>) =
        failClosed(referencesOf(target, policy)) { references.suspending().put(ReferenceKey(target, policy), entries) }

    suspend fun documents(): Set<String> = failClosed(DOCUMENTS_WHAT) { documents.suspending().get(DOCUMENT_SET) }

    suspend fun listDocuments(entries: Collection<String>) {
        if (entries.isNotEmpty()) {
            failClosed(DOCUMENTS_WHAT) { documents.suspending().add(DOCUMENT_SET, entries) }
        }
    }

    private fun referencesOf(target: SnapshotKey, policy: PolicyId): String =
        "The back-reference set of [$target] policy [${SnapshotKeyText.printable(policy.value)}]"

    /**
     * [block] over a set, failing closed with what the failure is (R2C-7): a value CoCache reads as a plain value, not a
     * set, is the format before 8.0.0-beta.5, and only that failure names the command that converts it; a member that
     * does not decode is a format error naming the set; anything else, a timeout or a failover, passes through as it is.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> failClosed(what: String, block: suspend () -> T): T = try {
        block()
    } catch (error: RuntimeException) {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        when {
            chain.any(::isOldFormat) -> throw IllegalStateException(
                "$what is of the format before 8.0.0-beta.5 (${error.message}). Convert the store first: run " +
                    "`cosec-migrate repair-references`.",
                error,
            )
            chain.any { it is SnapshotFormatException } -> throw SnapshotFormatException(
                "${SnapshotKeyText.printable(what)} does not read: " +
                    chain.first { it is SnapshotFormatException }.message,
                error,
            )
            else -> throw error
        }
    }

    companion object {
        /** What CoCache throws for a key that holds a plain value where a set is expected. */
        private fun isOldFormat(error: Throwable): Boolean =
            error is ForeignFormatException || error.message?.contains("expected a set but found a scalar") == true

        /** The document set's one key. */
        private const val DOCUMENT_SET = "all"
        private const val DOCUMENTS_WHAT = "The document set"

        /** Both sets under [keyPrefix], with CoSec's locks. */
        fun create(factory: CoCacheFactory, keyPrefix: String): ControlSets = ControlSets(
            factory.createLocked(ControlCacheSpecs.referenceIndex(keyPrefix)),
            factory.createLocked(ControlCacheSpecs.documentSet(keyPrefix)),
        )
    }
}
