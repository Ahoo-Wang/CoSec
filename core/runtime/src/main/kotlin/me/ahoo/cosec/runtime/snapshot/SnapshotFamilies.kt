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

package me.ahoo.cosec.runtime.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.util.concurrent.ConcurrentHashMap

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/**
 * The families of a [SnapshotSource] (RFC 0013 B2a): each kind's slots by id, their eviction beyond the family's bounds
 * (`SNP-7`), and the revisions seen per key, which outlive eviction. Iterating them visits each family once, in the
 * order the source was given them.
 */
internal class SnapshotFamilies<A : Any>(
    specs: List<SnapshotFamily<A>>,
    sharedKind: SnapshotKind,
    private val lagReports: LagReports,
) : Iterable<SnapshotFamilies.Family<A>> {
    private val families: List<Family<A>> = specs.map { Family(it) }
    private val evictionLock = Any()

    init {
        require(
            this.families.map { it.spec.kind }.toSet().size == this.families.size
        ) { "Families must differ in kind." }
        require(this.families.none { it.spec.kind == sharedKind }) { "The shared key cannot be a family's." }
    }

    /** The last decoded revision read or installed per key, kept across eviction (see [SnapshotSource]). */
    val seen = ConcurrentHashMap<SnapshotKey, Seen>()

    override fun iterator(): Iterator<Family<A>> = families.iterator()

    /**
     * Evicts the least recently used idle entries of [family] beyond its bounds: documents beyond
     * [SnapshotFamily.maxEntries], and entries for documents the store does not hold beyond [SnapshotFamily.maxAbsent].
     * Each kind evicts only its own, and a load in flight counts toward neither (`SNP-7`).
     */
    fun evictBeyond(family: Family<*>) {
        val slots = family.slots
        if (slots.size <= minOf(family.spec.maxEntries, family.spec.maxAbsent)) {
            return
        }
        synchronized(evictionLock) {
            evictBeyond(slots, family.spec.maxEntries, absent = false)
            evictBeyond(slots, family.spec.maxAbsent, absent = true)
        }
    }

    private fun evictBeyond(slots: ConcurrentHashMap<String, out Slot<*>>, bound: Int, absent: Boolean) {
        while (true) {
            var held = 0
            var victim: Slot<*>? = null
            for (slot in slots.values) {
                val entry = slot.installed ?: continue
                if ((entry.origin == EntryOrigin.ABSENT) != absent) {
                    continue
                }
                held++
                if ((victim == null || slot.lastAccess < victim.lastAccess) && slot.isIdle()) {
                    victim = slot
                }
            }
            if (held <= bound || victim == null) {
                return
            }
            if (victim.removeIfIdle(onlyIfEmpty = false)) {
                lagReports.forget(victim.key)
                log.debug { "Evicted snapshot document [${victim.key}]." }
            }
        }
    }

    /** One family's documents, by id. */
    class Family<A : Any>(val spec: SnapshotFamily<A>) {
        val slots = ConcurrentHashMap<String, Slot<A>>()

        /** Drops [slot] from the map, if it is still the one held for [id]. */
        fun drop(id: String, slot: Slot<*>) {
            slots.computeIfPresent(id) { _, current -> current.takeUnless { it === slot } }
        }
    }

    /** Registers the decoded revision of [entry]; returns the previous one when [entry] regresses from it. */
    fun recordSeen(entry: SnapshotEntry<*>): Seen? {
        if (entry.revision == 0L) {
            return null
        }
        val previous = seen.put(entry.key, Seen(entry.revision, entry.digest)) ?: return null
        val regressed = entry.revision < previous.revision ||
            (entry.revision == previous.revision && entry.digest != previous.digest)
        return previous.takeIf { regressed }
    }

    class Seen(val revision: Long, val digest: SnapshotDigest?)
}
