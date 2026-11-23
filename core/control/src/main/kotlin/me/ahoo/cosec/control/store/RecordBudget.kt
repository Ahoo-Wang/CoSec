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
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * What a document's records may weigh (`CTL-84`, DR1-F-6): its [RetentionPolicy.maxCount] most recent revisions always
 * stay, so a rollback to any of them works; beyond them, its records weigh at most [RetentionPolicy.byteBudget] and its
 * index lists at most [RetentionPolicy.maxEntries] more. Past either, the oldest go first, younger than `min-age` or not,
 * unless an open change rests on one: a loop of large publishes no longer fills Redis for a week.
 *
 * Reclaiming a record younger than `min-age` breaks that guarantee for its document, so each one is counted
 * ([reclaimedYoung]) and warned of, at most once a reclaim pass for all documents, naming the last.
 */
internal class RecordBudget(private val clock: Clock) {
    /** Records reclaimed younger than `min-age` by this instance since it started: `cosec.control.revisions.reclaimed-young`. */
    val reclaimedYoung = AtomicLong()

    private var warnedAt: Instant? = null
    private var unwarned = 0L

    /**
     * The digests of [entries], oldest first, past [retention]'s budgets counted from the newest, never one of its
     * [RetentionPolicy.maxCount] most recent. [sizes] holds what an entry without its size weighs.
     */
    fun over(
        entries: List<RevisionEntry>,
        sizes: Map<SnapshotDigest, Long>,
        retention: RetentionPolicy
    ): Set<SnapshotDigest> {
        val over = HashSet<SnapshotDigest>()
        var bytes = 0L
        entries.asReversed().forEachIndexed { newest, entry ->
            bytes += entry.size ?: sizes[entry.digest] ?: 0
            val beyond = newest - retention.maxCount
            if (beyond >= 0 && (bytes > retention.byteBudget || beyond >= retention.maxEntries)) {
                over += entry.digest
            }
        }
        return over
    }

    /** [young], reclaimed from [key] younger than `min-age`: counted, and warned of at most once a pass. */
    fun reclaimed(key: SnapshotKey, young: List<RevisionEntry>, retention: RetentionPolicy) {
        if (young.isEmpty()) return
        reclaimedYoung.addAndGet(young.size.toLong())
        val now = clock.instant()
        val (warn, before) = synchronized(this) {
            unwarned += young.size
            val due = warnedAt?.plus(RetentionPolicy.RECLAIM_PASS)?.isAfter(now) != true
            val count = unwarned
            if (due) {
                warnedAt = now
                unwarned = 0
            }
            due to count - young.size
        }
        if (warn) {
            log.warn {
                "[$key]: ${young.size} records younger than ${retention.minAge} reclaimed (${young.sumOf { it.size ?: 0 }} " +
                    "bytes), past the most a document retains (${retention.byteBudget} bytes or ${retention.maxEntries} " +
                    "entries beyond the ${retention.maxCount} most recent, CTL-84); $before more since the last warning."
            }
        }
    }
}
