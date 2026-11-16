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
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
     * Evicts the least recently used idle entries of [family] beyond its bounds, at most [limit] of each kind: documents
     * beyond [SnapshotFamily.maxEntries], and entries for documents the store does not hold beyond
     * [SnapshotFamily.maxAbsent]. Each kind evicts only its own, and a load in flight counts toward neither (`SNP-7`).
     *
     * One scan of the family's slots picks every victim of a kind; each removal first claims its decrement of that
     * kind's count, which never goes below the bound, and gives it back when the victim is refused (busy, removed, or
     * of the other kind by now). So passes running at once, here and on installing threads, never take a kind below its
     * bound between them, and a victim two passes chose is removed once (`SNP-14`).
     */
    fun evictBeyond(family: Family<*>, limit: Int = Int.MAX_VALUE) {
        evictBeyond(family, absent = false, limit)
        evictBeyond(family, absent = true, limit)
    }

    /** Whether [family] holds more entries of a kind than its cap ([Family.cap]): its installer evicts in place. */
    fun overCap(family: Family<*>): Boolean = family.overCap(absent = false) || family.overCap(absent = true)

    private fun evictBeyond(family: Family<*>, absent: Boolean, limit: Int) {
        val excess = family.count(absent).get() - family.bound(absent)
        if (excess <= 0) {
            return
        }
        for (victim in victims(family, absent, minOf(excess, limit))) {
            evictOne(family, victim, absent) ?: return
        }
    }

    /**
     * Evicts [victim], an entry of the kind [absent] names when it was chosen: claims the decrement first, removes it if
     * it is still idle and of that kind, and gives the claim back otherwise. Null when there was nothing to claim (the
     * kind is at its bound), else whether it went.
     */
    fun evictOne(family: Family<*>, victim: Slot<*>, absent: Boolean): Boolean? {
        if (!family.claim(absent)) {
            return null
        }
        if (victim.removeIfIdle(onlyIfEmpty = false, claimedAbsent = absent)) {
            lagReports.forget(victim.key)
            log.debug { "Evicted snapshot document [${victim.key}]." }
            return true
        }
        family.count(absent).incrementAndGet()
        return false
    }

    /** The [wanted] least recently used idle slots holding an entry of the kind [absent] names, in one scan. */
    internal fun victims(family: Family<*>, absent: Boolean, wanted: Int): List<Slot<*>> {
        if (wanted <= 0) {
            return emptyList()
        }
        // The most recently used of the chosen on top, so that a less recently used slot replaces it.
        val chosen = PriorityQueue<Slot<*>>(compareByDescending { it.lastAccess })
        for (slot in family.slots.values) {
            val entry = slot.installed ?: continue
            if (isAbsent(entry) != absent) {
                continue
            }
            // Only a slot that would be chosen is asked whether it is idle: that takes its monitor.
            val full = chosen.size >= wanted
            if (full && slot.lastAccess >= chosen.peek().lastAccess) {
                continue
            }
            if (!slot.isIdle()) {
                continue
            }
            chosen.add(slot)
            if (full) {
                chosen.poll()
            }
        }
        return chosen.sortedBy { it.lastAccess }
    }

    /** Moves [family]'s counts for an entry replaced by another, either of them null: where `installed` is written. */
    fun move(family: Family<*>, old: SnapshotEntry<*>?, new: SnapshotEntry<*>?) {
        val from = old?.let(::isAbsent)
        val to = new?.let(::isAbsent)
        if (from == to) {
            return
        }
        from?.let { family.count(it).decrementAndGet() }
        to?.let { family.count(it).incrementAndGet() }
    }

    /** One family's documents, by id, and how many of each kind it holds (`SNP-14`). */
    class Family<A : Any>(val spec: SnapshotFamily<A>) {
        val slots = ConcurrentHashMap<String, Slot<A>>()

        /** The documents held (every failed entry is one) and the entries for documents the store does not hold. */
        private val documents = AtomicInteger()
        private val absentEntries = AtomicInteger()

        fun count(absent: Boolean): AtomicInteger = if (absent) absentEntries else documents

        fun bound(absent: Boolean): Int = if (absent) spec.maxAbsent else spec.maxEntries

        /** The most a kind may hold before its installer evicts in place: twice the bound, at most [MAX_SLACK] above it. */
        fun cap(absent: Boolean): Int = bound(absent).let { it + minOf(it, MAX_SLACK) }

        fun overCap(absent: Boolean): Boolean = count(absent).get() > cap(absent)

        /** Claims one removal of a kind's entry: its count's decrement, never below the bound; false at the bound. */
        fun claim(absent: Boolean): Boolean {
            val bound = bound(absent)
            return count(absent).getAndUpdate { if (it > bound) it - 1 else it } > bound
        }

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

    companion object {
        /**
         * How far above its bound a family's kind may go before the installing thread evicts in place (`SNP-14`): the
         * cap is the bound plus the smaller of the bound and this. A rule of its own, not the housekeeping queue's size.
         */
        const val MAX_SLACK = 4096

        /** Whether [entry] is for a document the store does not hold: the other kind ([Family.count]). */
        fun isAbsent(entry: SnapshotEntry<*>): Boolean = entry.origin == EntryOrigin.ABSENT
    }
}
