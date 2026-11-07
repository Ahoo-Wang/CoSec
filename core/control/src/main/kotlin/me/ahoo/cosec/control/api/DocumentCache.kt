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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The stored documents `simulate`, `explain` and `analyze` read, each decoded and compiled once for its content (RC1-41;
 * RFC 0007 §10.3 caches compiled versions): by its key and the digest of what is stored, so a head that moves, or a
 * retained revision, is another entry. An app or tenant document compiles with the platform document's limits and
 * attributes, so its compiled form is kept by the digest of the platform document it compiled with. Bounded by
 * [maxEntries] and by [maxBytes] of stored content, the least recently used leaving first. A candidate, which no digest
 * names, is never kept: [Simulator] compiles it once per call.
 */
internal class DocumentCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxEntries > 0 && maxBytes > 0) { "The cache holds at least one document." }
    }

    private data class Key(val key: SnapshotKey, val digest: SnapshotDigest)

    private val entries = LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var bytes = 0L

    /** A stored document: [key] at [digest], decoded. */
    class Entry(val key: SnapshotKey, val digest: SnapshotDigest, val document: PolicyDocument, val size: Int) {
        /** Its compiled forms by the platform document's digest they compiled with, at most [VARIANTS]. */
        private val compiled = LinkedHashMap<SnapshotDigest?, Lazy<Result<CompiledDocument>>>()

        /**
         * Its compiled form with the platform document of digest [platform] (null: none), by [compile] the first time.
         * Concurrent first calls compile once; a compilation that fails is kept as failed, since it fails again.
         */
        fun compiled(platform: SnapshotDigest?, compile: () -> CompiledDocument): Result<CompiledDocument> {
            val compilation = synchronized(compiled) {
                compiled.getOrPut(platform) {
                    lazy(LazyThreadSafetyMode.SYNCHRONIZED) { compiling(compile) }
                }.also {
                    if (compiled.size > VARIANTS) compiled.remove(compiled.keys.first())
                }
            }
            return compilation.value
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
            document.policies.forEach { policies.putIfAbsent(it.id.value, AnalyzedPolicy(it)) }
            policies
        }
    }

    /** A policy, its statements' action and resource patterns parsed when `analyze` first reaches it. */
    class AnalyzedPolicy(val definition: PolicyDefinition) {
        val statements: List<AnalyzedStatement> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            definition.statements.mapIndexed { index, statement -> AnalyzedStatement(index, statement) }
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
     * that put one first wins, so every caller compiles with the same entry.
     */
    fun put(
        key: SnapshotKey,
        digest: SnapshotDigest,
        size: Int,
        document: PolicyDocument
    ): Entry = synchronized(entries) {
        entries[Key(key, digest)]?.let { return it }
        val entry = Entry(key, digest, document, size)
        entries[Key(key, digest)] = entry
        bytes += size
        evict()
        entry
    }

    private fun evict() {
        val iterator = entries.values.iterator()
        // The newest entry stays even when it alone is over the size: a call holds it until it returns.
        while ((entries.size > maxEntries || bytes > maxBytes) && entries.size > 1) {
            bytes -= iterator.next().size
            iterator.remove()
        }
    }

    /** How many documents it holds; for tests. */
    val size: Int get() = synchronized(entries) { entries.size }

    companion object {
        /** Documents held at most: a platform, app and tenant document for each of the tenants simulated most recently. */
        const val DEFAULT_MAX_ENTRIES: Int = 64

        /** Stored bytes held at most: eight documents at the 8 MB cap (RFC 0003 Q14). */
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024

        /** Compiled forms kept per document: the current platform's, and the previous one's while a change propagates. */
        private const val VARIANTS = 2
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
