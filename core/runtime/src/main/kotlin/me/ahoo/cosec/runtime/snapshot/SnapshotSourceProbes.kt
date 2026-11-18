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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind

// What tests and benchmarks read and drive of a source's family bound (`SNP-14`), beside it rather than in it.

/** Tests only: what the family of [kind] counts it holds, documents then absent entries. */
internal fun SnapshotSource<*, *>.heldCounts(kind: SnapshotKind): Pair<Int, Int> =
    familyOfKind(kind).let { it.count(absent = false).get() to it.count(absent = true).get() }

/** Tests only: the keys the family of [kind] lists as holding an absent entry. */
internal fun SnapshotSource<*, *>.heldAbsentMembers(kind: SnapshotKind): Set<SnapshotKey> =
    familyOfKind(kind).absentMembers.mapTo(HashSet()) { it.key }

/** Tests only: how many slots a pass choosing [wanted] victims of the kind [absent] names reads the stamp of. */
internal fun SnapshotSource<*, *>.evictionVisits(kind: SnapshotKind, absent: Boolean, wanted: Int): Int {
    var visits = 0
    val stamp = { slot: KeyRunner<*> -> slot.lastAccess.also { visits++ } }
    families.victims(familyOfKind(kind), absent, wanted, stamp)
    return visits
}

/** Benchmarks only: one eviction pass over the [kind] family's documents, at most [limit] of them. */
internal fun SnapshotSource<*, *>.evictionPass(
    kind: SnapshotKind,
    limit: Int
) = families.evictBeyond(familyOfKind(kind), limit)

/** Benchmarks only: the scan of an eviction pass alone, choosing [wanted] victims of the kind [absent] names. */
internal fun SnapshotSource<*, *>.evictionScan(kind: SnapshotKind, wanted: Int, absent: Boolean = false): Int =
    families.victims(familyOfKind(kind), absent, wanted).size

/** Tests only: one claimed eviction of [key]'s slot, as a pass that chose it as of the kind [absent] would do it. */
internal fun SnapshotSource<*, *>.evictClaiming(key: SnapshotKey, absent: Boolean): Boolean? {
    val family = familyOfKind(key.kind)
    val slot = family.slots[family.spec.idOf(key)] ?: return false
    return when (families.evictOne(slot, absent).join()) {
        Eviction.REMOVED -> true
        Eviction.REFUSED -> false
        Eviction.AT_BOUND, null -> null
    }
}

/** Tests only: the [kind] family's slots with a load in flight. */
internal fun SnapshotSource<*, *>.loadsInFlight(kind: SnapshotKind): Int = familyOfKind(kind).slots.values.count {
    !it.idle()
}

/**
 * Tests only: nothing in motion, no key loading, queued or draining, housekeeping idle (RFC 0013 B2b step 3). A test's
 * read: no production path branches on it.
 */
internal fun SnapshotSource<*, *>.quiescent(): Boolean =
    shared.quiescent() && families.all { it.slots.values.all(KeyRunner<*>::quiescent) } && threads.housekeepingIdle

private fun SnapshotSource<*, *>.familyOfKind(kind: SnapshotKind): SnapshotFamilies.Family<*> =
    families.first { it.spec.kind == kind }
