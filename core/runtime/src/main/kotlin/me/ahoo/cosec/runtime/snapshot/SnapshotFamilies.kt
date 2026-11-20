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
import java.util.concurrent.CompletableFuture
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
    private val metrics: SnapshotMetrics,
) : Iterable<SnapshotFamilies.Family<A>> {
    private val families: List<Family<A>> = specs.map { Family(it) }

    init {
        require(
            this.families.map { it.spec.kind }.toSet().size == this.families.size
        ) { "Families must differ in kind." }
        require(this.families.none { it.spec.kind == sharedKind }) { "The shared key cannot be a family's." }
    }

    /**
     * Each family at its kind's ordinal, so finding one is an array read: `peek` allocates no iterator for it. Sized by
     * [SnapshotKind.entries], so every kind, one added later included, has a slot: a kind with no family reads null,
     * what the search by kind answered, and no read can fall outside the array.
     */
    private val byKind: Array<Family<A>?> = arrayOfNulls<Family<A>>(SnapshotKind.entries.size).also { byKind ->
        families.forEach { byKind[it.spec.kind.ordinal] = it }
    }

    /** The family of [kind]; null when this source has none. */
    fun of(kind: SnapshotKind): Family<A>? = byKind[kind.ordinal]

    /** The last decoded revision read or installed per key, kept across eviction (see [SnapshotSource]). */
    val seen = ConcurrentHashMap<SnapshotKey, Seen>()

    override fun iterator(): Iterator<Family<A>> = families.iterator()

    /**
     * Evicts the least recently used idle entries of [family] beyond its bounds, at most [limit] of each kind: documents
     * beyond [SnapshotFamily.maxEntries], and entries for documents the store does not hold beyond
     * [SnapshotFamily.maxAbsent]. Each kind evicts only its own, and a load in flight counts toward neither (`SNP-7`).
     *
     * One scan of the family's slots picks every victim of a kind; each removal claims its decrement of that kind's
     * count, which never goes below the bound, under the victim's lock and only once nothing can refuse it (busy,
     * removed, or of the other kind by now). So passes running at once, here and on installing threads, never take a kind
     * below its bound between them, never stop above it because of a claim another pass was about to give back, and a
     * victim two passes chose is removed once (`SNP-14`).
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
            // A reply already complete at the bound stops the pass; one not yet complete never
            // holds it back: a claim cannot take a kind below its bound, whatever is offered (§4b, decision B).
            if (evictOne(victim, absent).getNow(null) == Eviction.AT_BOUND) return
        }
    }

    /**
     * Offers the eviction of [victim], an entry of the kind [absent] names when it was chosen: removed if it is still idle
     * and of that kind, the claim taken by the victim itself (`SNP-14`).
     */
    fun evictOne(victim: KeyRunner<*>, absent: Boolean): CompletableFuture<Eviction> =
        victim.evict(onlyIfEmpty = false, claimedAbsent = absent)

    /** [key] left its family, holding [entry]: what a removal does besides leaving the map. */
    fun removed(key: SnapshotKey, entry: SnapshotEntry<*>?) {
        lagReports.forget(key)
        // Only an installed entry that leaves is an eviction. A key dropped empty, after a cold load that failed or
        // timed out, held nothing: counting it would put noise in the meter that says the bound is too small.
        if (entry != null) {
            metrics.onEvicted(key.kind, isAbsent(entry))
        }
        log.debug { "Removed snapshot document [$key]." }
    }

    /** The slot the family of [key] holds for it now, made if it holds none; for [KeyContext.current]. */
    fun slotFor(key: SnapshotKey, context: KeyContext): KeyRunner<*> {
        // NoSuchElementException, as the search by kind threw before.
        val family = of(key.kind) ?: throw NoSuchElementException("This source has no ${key.kind} documents.")
        return family.slotFor(key, context)
    }

    /** The [wanted] least recently used idle slots holding an entry of the kind [absent] names, in one scan. */
    internal inline fun victims(
        family: Family<*>,
        absent: Boolean,
        wanted: Int,
        lastAccessOf: (KeyRunner<*>) -> Long = { it.lastAccess },
    ): List<KeyRunner<*>> = leastRecentlyUsed(
        candidates(family, absent),
        wanted,
        // A slot is checked again, since its kind can change after it was listed.
        { slot -> slot.installed?.let { isAbsent(it) == absent } == true },
        lastAccessOf,
        { it.idle() },
    )

    /**
     * What a pass for the kind [absent] names scans: absent entries from their own set, documents from the family's map.
     * A set of the documents made their warmed scan 1.6 times slower at a bound of 100,000 (2.25-2.42 ms against
     * 3.7-4.1 ms, `EvictionPassBenchmark`), for nothing their pass uses.
     */
    fun candidates(family: Family<*>, absent: Boolean): Iterable<KeyRunner<*>> =
        if (absent) family.absentMembers else family.slots.values

    /** Moves [family]'s counts for an entry replaced by another, either of them null: where `installed` is written. */
    fun move(family: Family<*>, slot: KeyRunner<*>, old: SnapshotEntry<*>?, new: SnapshotEntry<*>?) {
        val from = old?.let(::isAbsent)
        val to = new?.let(::isAbsent)
        if (from == to) {
            return
        }
        from?.let { family.leave(slot, it) }
        to?.let { family.join(slot, it) }
    }

    /** One family's documents, by id, and how many of each kind it holds (`SNP-14`). */
    class Family<A : Any>(val spec: SnapshotFamily<A>) {
        val slots = ConcurrentHashMap<String, KeyRunner<A>>()

        /** The documents held (every failed entry is one) and the entries for documents the store does not hold. */
        private val documents = AtomicInteger()
        private val absentEntries = AtomicInteger()

        /**
         * The slots holding an absent entry, beside the counts and moved with them: the absent kind's pass walks these
         * alone, so it costs what that kind holds, however many documents the family holds, and the documents' pass walks
         * the family's map. Either scan costs about the same per entry it walks (0.5 to 1.3 ms for 54,000 of them): a
         * measured gap between the two came from tenant ids that made the map's order follow allocation order. Read only
         * by eviction passes and tests; `peek` never sees it.
         */
        private val absentSlots: MutableSet<KeyRunner<*>> = ConcurrentHashMap.newKeySet()

        val absentMembers: Set<KeyRunner<*>> get() = absentSlots

        fun count(absent: Boolean): AtomicInteger = if (absent) absentEntries else documents

        fun join(slot: KeyRunner<*>, absent: Boolean) {
            if (absent) absentSlots += slot
            count(absent).incrementAndGet()
        }

        fun leave(slot: KeyRunner<*>, absent: Boolean) {
            if (absent) absentSlots -= slot
            count(absent).decrementAndGet()
        }

        fun bound(absent: Boolean): Int = if (absent) spec.maxAbsent else spec.maxEntries

        /** The most a kind may hold before its installer evicts in place: twice the bound, at most [MAX_SLACK] above it. */
        fun cap(absent: Boolean): Int = bound(absent).let { it + minOf(it, MAX_SLACK) }

        fun overCap(absent: Boolean): Boolean = count(absent).get() > cap(absent)

        /**
         * Claims the removal of [slot], an entry of the kind [absent] names: its count's decrement, never below the
         * bound, and for an absent entry its place among [absentMembers]; false at the bound, and nothing moves.
         */
        fun claim(slot: KeyRunner<*>, absent: Boolean): Boolean {
            val bound = bound(absent)
            val claimed = count(absent).getAndUpdate { if (it > bound) it - 1 else it } > bound
            if (claimed && absent) {
                absentSlots -= slot
            }
            return claimed
        }

        /**
         * The slot held for [key], made if none is; a removed one still in the map is dropped here, so that a lookup never
         * waits for the remover (what `withSlot`'s retry did).
         */
        fun slotFor(key: SnapshotKey, context: KeyContext): KeyRunner<A> {
            val id = spec.idOf(key)
            while (true) {
                val slot = slots.computeIfAbsent(id) { context.runners.create(key, spec.compiler, context) }
                if (!slot.removed) return slot
                slots.remove(id, slot)
            }
        }

        /** A claim of [slot]'s removal given back, its removal not having happened: the count, and its absent place. */
        fun giveBack(slot: KeyRunner<*>, absent: Boolean) {
            count(absent).incrementAndGet()
            if (absent) absentSlots += slot
        }

        /** Drops [slot] from the map, if it is still the one held for [id]. */
        fun drop(id: String, slot: KeyRunner<*>) {
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

        /**
         * The most entries of a kind an install evicts on its own thread when it finds the family over its cap
         * (`SNP-14`): enough to take the family back within the cap, since at most one entry per install in flight is
         * over it, and few enough that the thread pays one scan and a few dozen removals (about 2 to 3.6 ms warm at the
         * default bounds), never a whole pass back to the bound (4 to 9 ms warm, 21 to 33 ms cold, at bounds of 16,384
         * to 65,536: measured, beta.5). Housekeeping evicts the rest.
         */
        const val INSTALL_EVICTIONS = 64

        /** Whether [entry] is for a document the store does not hold: the other kind ([Family.count]). */
        fun isAbsent(entry: SnapshotEntry<*>): Boolean = entry.origin == EntryOrigin.ABSENT

        /**
         * The [wanted] least recently used of the [slots] that are [listed] and [eligible], least recent first, in one pass,
         * inlined into its caller as the plain loop it replaced (a sequence and three function calls per slot cost the scan
         * up to a fifth, `EvictionPassBenchmark`). Each slot's
         * stamp is read once, through [lastAccessOf], and the heap and the final order use that value: requests write the
         * stamp from any thread as they peek, and ordering by a value that moves under it would choose wrongly or, once
         * the sort is TimSort (32 elements or more), throw "Comparison method violates its general contract". Only a
         * slot that would be chosen is asked whether it is [eligible] (idle: that takes its monitor), and only it
         * allocates.
         */
        internal inline fun <S : Any> leastRecentlyUsed(
            slots: Iterable<S>,
            wanted: Int,
            listed: (S) -> Boolean,
            lastAccessOf: (S) -> Long,
            eligible: (S) -> Boolean,
        ): List<S> {
            if (wanted <= 0) {
                return emptyList()
            }
            // The most recently used of the chosen on top, so that a less recently used slot replaces it.
            val chosen = PriorityQueue<Stamped<S>>(compareByDescending { it.lastAccess })
            for (slot in slots) {
                if (!listed(slot)) {
                    continue
                }
                val lastAccess = lastAccessOf(slot)
                val full = chosen.size >= wanted
                if (full && lastAccess >= chosen.peek().lastAccess) {
                    continue
                }
                if (!eligible(slot)) {
                    continue
                }
                chosen.add(Stamped(slot, lastAccess))
                if (full) {
                    chosen.poll()
                }
            }
            return chosen.sortedBy { it.lastAccess }.map { it.slot }
        }

        /** A slot with its stamp as the scan read it, once. */
        internal class Stamped<S>(val slot: S, val lastAccess: Long)
    }
}
