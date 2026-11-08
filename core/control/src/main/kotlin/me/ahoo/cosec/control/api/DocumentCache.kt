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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.RoleExpansion
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The stored documents `simulate`, `explain` and `analyze` read, each decoded and compiled once for its content (RC1-41;
 * RFC 0007 §10.3 caches compiled versions): by its key and the digest of what is stored, so a head that moves is another
 * entry. An app or tenant document compiles with the platform document's limits and attributes, so its compiled form is
 * kept by the digest of the platform document it compiled with.
 *
 * Bounded by [maxEntries] and by [maxHeap], an estimate of the heap it holds, the least recently used leaving first
 * (review M2 of #1240): a document weighs its stored size times [DECODED_FACTOR] once decoded, and times
 * [COMPILED_FACTOR] more for each compiled form it keeps, the factors measured on documents of many policies, many
 * bindings and many conditions (`DocumentCacheTest`). A document weighs [DERIVED_BINDING_BYTES] more stored bytes for
 * each binding its roles derive (RFC 0012 §6.3): a few roles may expand far beyond what they store. What `analyze` parses
 * of it is not weighed. A candidate, which no
 * digest names, is never kept, nor is a retained revision `explain` decides at: [Simulator] compiles those per call. Nor
 * is a document that, decoded and compiled once, would weigh more than half the bound (R2-L2 of #1240's review): it
 * would push every other entry out, and be pushed out by the next one, so it is decoded and compiled per call too.
 */
internal class DocumentCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxHeap: Long = DEFAULT_MAX_HEAP,
) {
    init {
        require(maxEntries > 0 && maxHeap > 0) { "The cache holds at least one document." }
    }

    private data class Key(val key: SnapshotKey, val digest: SnapshotDigest)

    private val entries = LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var heap = 0L

    /** A stored document: [key] at [digest], decoded, of [size] stored bytes. */
    inner class Entry internal constructor(
        val key: SnapshotKey,
        val digest: SnapshotDigest,
        val document: PolicyDocument,
        val size: Int,
    ) {
        /** Its compiled forms by the platform document's digest they compiled with, at most [VARIANTS]. */
        private val compiled = LinkedHashMap<SnapshotDigest?, Lazy<Result<CompiledDocument>>>()

        /** What it is counted for in [heap], while it is held: guarded by the cache's lock. */
        internal var weight: Long = weighedBytes(size, document) * DECODED_FACTOR

        /** What is enforced: the roles and short forms of [document], expanded, for `analyze` as for the engine. */
        val effective: PolicyDocument by lazy(LazyThreadSafetyMode.PUBLICATION) { RoleExpansion.effective(document) }

        /**
         * Its compiled form with the platform document of digest [platform] (null: none), by [compile] the first time.
         * Concurrent first calls compile once; a compilation that fails is kept as failed, since it fails again. A new
         * form adds its weight, and one it drops takes it away.
         */
        fun compiled(platform: SnapshotDigest?, compile: () -> CompiledDocument): Result<CompiledDocument> {
            val (compilation, added) = synchronized(compiled) { formFor(platform, compile) }
            if (added) {
                weigh(this, weighedBytes(size, document) * COMPILED_FACTOR)
            }
            return compilation.value
        }

        /**
         * The form for [platform], and whether it is a new one that adds its weight: one more than [VARIANTS] makes the
         * oldest leave, whose weight goes as the new one's comes, so nothing is added then. Called under the forms' lock.
         */
        private fun formFor(
            platform: SnapshotDigest?,
            compile: () -> CompiledDocument,
        ): Pair<Lazy<Result<CompiledDocument>>, Boolean> {
            compiled[platform]?.let { return it to false }
            val created = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { compiling(compile) }
            compiled[platform] = created
            if (compiled.size <= VARIANTS) {
                return created to true
            }
            compiled.remove(compiled.keys.first())
            return created to false
        }

        @Suppress("TooGenericExceptionCaught")
        private fun compiling(compile: () -> CompiledDocument): Result<CompiledDocument> = try {
            Result.success(compile())
        } catch (e: Exception) {
            Result.failure(e)
        }

        /** Its policies by id as `analyze` matches them, the first of an id: each one's patterns parsed once (RC1-41). */
        val analyzed: Map<String, AnalyzedPolicy> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val policies = LinkedHashMap<String, AnalyzedPolicy>()
            effective.policies.forEach { policies.putIfAbsent(it.id.value, AnalyzedPolicy(it, this)) }
            policies
        }
    }

    /**
     * A policy, its statements' action and resource patterns parsed when `analyze` first reaches it. A pattern this
     * version cannot parse is a stored value it cannot read (review L8 of #1240): a [SnapshotFormatException] naming the
     * document, which the control API answers 500 and logs, never the caller's 400.
     */
    class AnalyzedPolicy internal constructor(
        val definition: PolicyDefinition,
        private val entry: DocumentCache.Entry
    ) {
        val statements: List<AnalyzedStatement> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            try {
                definition.statements.mapIndexed { index, statement -> AnalyzedStatement(index, statement) }
            } catch (e: IllegalArgumentException) {
                throw SnapshotFormatException(
                    "The stored document [${entry.key}] revision ${entry.document.revision} has a pattern in policy " +
                        "[${definition.id.value}] this version cannot parse: ${e.message}",
                    e
                )
            }
        }
    }

    class AnalyzedStatement(val index: Int, val source: StatementSource) {
        private val actions = source.actions.map { ActionPattern.parse(it) }

        /** Empty for a statement without resources, which matches every resource. */
        private val resources = source.resources.map {
            ResourcePattern.parse(it.pattern, it.caseSensitive, it.decodeAndParseSegments)
        }

        fun matches(action: Action, target: ResourceTarget): Boolean =
            actions.any { it.matches(action) } && (resources.isEmpty() || resources.any { it.matches(target) })
    }

    /** The entry of [key] at [digest], or null. */
    fun get(key: SnapshotKey, digest: SnapshotDigest): Entry? = synchronized(entries) { entries[Key(key, digest)] }

    /**
     * The entry of [key] at [digest]: the one held, or [document] as a new one of [size] stored bytes. Another call
     * that put one first wins, so every caller compiles with the same entry. A document too heavy to admit
     * ([admits]) is an entry of its own, held by no one but the call: what it compiles goes with it.
     */
    fun put(
        key: SnapshotKey,
        digest: SnapshotDigest,
        size: Int,
        document: PolicyDocument
    ): Entry = synchronized(entries) {
        entries[Key(key, digest)]?.let { return it }
        val entry = Entry(key, digest, document, size)
        if (!admits(weighedBytes(size, document))) {
            return entry
        }
        entries[Key(key, digest)] = entry
        heap += entry.weight
        evict()
        entry
    }

    /** Whether a document of [size] stored bytes, decoded and compiled once, weighs at most half the bound. */
    fun admits(size: Long): Boolean = size * (DECODED_FACTOR + COMPILED_FACTOR) <= maxHeap / 2

    /** The stored bytes [document] weighs as: its own [size], and what its roles derive beyond it. */
    private fun weighedBytes(size: Int, document: PolicyDocument): Long =
        size.toLong() + RoleExpansion.grants(document) * DERIVED_BINDING_BYTES

    /** [entry]'s weight changes by [delta]: counted only while it is held, and what it pushes over the bound leaves. */
    private fun weigh(entry: Entry, delta: Long) = synchronized(entries) {
        entry.weight += delta
        if (entries[Key(entry.key, entry.digest)] === entry) {
            heap += delta
            evict()
        }
    }

    private fun evict() {
        val iterator = entries.values.iterator()
        // The newest entry stays even when it alone is over the bound: a call holds it until it returns.
        while ((entries.size > maxEntries || heap > maxHeap) && entries.size > 1) {
            heap -= iterator.next().weight
            iterator.remove()
        }
    }

    /** How many documents it holds; for tests. */
    val size: Int get() = synchronized(entries) { entries.size }

    /** The heap it is estimated to hold; for tests. */
    val estimatedHeap: Long get() = synchronized(entries) { heap }

    companion object {
        /** Documents held at most: a platform, app and tenant document for each of the tenants simulated most recently. */
        const val DEFAULT_MAX_ENTRIES: Int = 64

        /** The estimated heap held at most: a document of 1 MB stored, decoded and compiled once, weighs 12 MB of it. */
        const val DEFAULT_MAX_HEAP: Long = 64L * 1024 * 1024

        /** A decoded document's heap per stored byte, at most about 3.8 as measured. */
        const val DECODED_FACTOR: Int = 4

        /** A compiled form's heap per stored byte of its document, at most about 7.5 as measured. */
        const val COMPILED_FACTOR: Int = 8

        /** What one derived binding would take stored, written out. */
        const val DERIVED_BINDING_BYTES: Long = 160

        /** Compiled forms kept per document: the current platform's, and the previous one's while a change propagates. */
        private const val VARIANTS = 2
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
