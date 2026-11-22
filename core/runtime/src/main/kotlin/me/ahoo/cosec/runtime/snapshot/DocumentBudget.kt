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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKind

/**
 * How the snapshot documents' share of the heap ([total] bytes, `HeapBudget.documents`) bounds the families (`SNP-24`):
 *
 * - the entries for documents the store does not hold get a fixed part of it, [absentShare], a sixteenth: ids a client
 *   can name count against it, never against what the real documents get (`SNP-7`). They are of one fixed size
 *   ([DocumentWeight.ABSENT_ENTRY_BYTES]), so their caps are counts: the tenant family [absentTenants], the app family
 *   [absentApps], a 256th of the part, since app ids come from clients and tenant ids from credentials;
 * - the documents get the rest, [resident]: each family's bound is the documents it holds plus the room left of the
 *   resident part, once both families' documents are weighed ([DocumentWeight]), divided by the typical weight of its
 *   own (its mean, outliers left out); before it holds any, by [TYPICAL] weight; never below [FLOOR], so a family keeps
 *   documents however large one is. A very heavy document takes its own weight from the room left, no more.
 */
@InternalCoSecApi
class DocumentBudget(val total: Long) {
    init {
        require(total > 0) { "The documents' share must be positive, not $total." }
    }

    val absentShare: Long get() = total / ABSENT_PARTS
    val resident: Long get() = total - absentShare
    val absentApps: Int get() = count(absentShare / APP_ABSENT_PARTS)
    val absentTenants: Int get() = count(absentShare - absentShare / APP_ABSENT_PARTS)

    /**
     * A family's bound when it holds [count] documents weighing [weight], of [typicalWeight] each leaving out the very
     * heavy ones (0: none held), and the other family's documents weigh [others]: what it holds plus the room left,
     * counted in documents of the typical weight. The room may be negative: then the bound is below what it holds.
     */
    fun bound(kind: SnapshotKind, count: Int, weight: Long, typicalWeight: Long, others: Long): Int {
        val room = resident - others - weight
        val each = (if (count > 0 && typicalWeight > 0) typicalWeight else typical(kind)).coerceAtLeast(1)
        return (count + Math.floorDiv(room, each)).coerceIn(FLOOR.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** The bound before a family holds any document: the first estimate the startup line names. */
    fun firstBound(kind: SnapshotKind): Int = bound(kind, 0, 0, 0, 0)

    private fun count(bytes: Long): Int =
        (bytes / DocumentWeight.ABSENT_ENTRY_BYTES).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    override fun toString(): String =
        "documents=${mib(total)} (absent entries ${mib(absentShare)}: $absentTenants tenants, $absentApps apps; " +
            "resident ${mib(resident)}: ${firstBound(SnapshotKind.TENANT)} tenants of the typical weight " +
            "${typical(SnapshotKind.TENANT)} B, or ${firstBound(SnapshotKind.APP)} apps of ${typical(SnapshotKind.APP)} B)"

    @InternalCoSecApi
    companion object {
        /** The absent entries' part: a sixteenth of the documents' share, a sixty-fourth of the heap. */
        const val ABSENT_PARTS: Long = 16

        /** The app family's part of the absent entries' part. */
        const val APP_ABSENT_PARTS: Long = 256

        /** The fewest documents a family's bound allows, however heavy they are. */
        const val FLOOR: Int = 64

        /** The weight assumed before a family holds any: the deployment page's tenant, and the harness's app. */
        val TYPICAL: Map<SnapshotKind, Long> = mapOf(SnapshotKind.TENANT to 34_554L, SnapshotKind.APP to 318_812L)

        private const val MIB = 1L shl 20

        fun typical(kind: SnapshotKind): Long = TYPICAL[kind] ?: error("No family holds $kind documents.")

        private fun mib(bytes: Long): String = "${bytes / MIB} MiB"
    }
}
