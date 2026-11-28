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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration

/** How long revision records are kept (RFC 0007 §5.7). */
@InternalCoSecApi
class RetentionPolicy(
    /** The most recent revisions along the chain that are always kept (`cosec.control.revisions.max-count`). */
    val maxCount: Int = 50,
    /** Revisions younger than this are always kept (`cosec.control.revisions.min-age`). */
    val minAge: Duration = Duration.ofDays(7),
    /** Records not on the chain are removed once this old (one reclaim pass, [RECLAIM_PASS]; the host passes `ControlDerived.ORPHAN_AGE`). */
    val orphanAge: Duration = RECLAIM_PASS,
    /** How long one instance holds the reclamation lease of a document. */
    val leaseDuration: Duration = Duration.ofMinutes(5),
    /** The most a document may store encoded (the change checks' cap, `ChangeOptions.maxDocumentBytes`). */
    val documentCap: Long = DEFAULT_DOCUMENT_CAP,
) {
    /**
     * The most bytes of records a document retains in all, its [maxCount] most recent included (`CTL-84`): [maxCount]
     * documents at the cap, 400 MB by default. The most recent always stay, and each is at most the cap, so they alone
     * never pass it; past it the oldest beyond them go, younger than [minAge] or not, unless an open change rests on one.
     */
    val byteBudget: Long get() = maxCount * documentCap

    /**
     * The most revisions a document's index lists beyond its [maxCount] most recent (`CTL-84`): the index is one stored
     * value, read and written twice a publish, so it holds no more than the cap at [INDEX_ENTRY_BYTES] an entry: 26,214
     * by default, where small records keep the byte budget far away.
     */
    val maxEntries: Int get() = (documentCap / INDEX_ENTRY_BYTES).toInt()

    init {
        require(maxCount >= 1) { "At least the head is kept." }
        require(documentCap > 0) { "A document cap is positive." }
        require(
            !minAge.isNegative && !orphanAge.isNegative && !leaseDuration.isNegative
        ) { "Durations are not negative." }
    }

    @InternalCoSecApi
    companion object {
        /** How often a reclaim pass runs, which is also how old an orphan must be: one pass, an hour. */
        val RECLAIM_PASS: Duration = Duration.ofHours(1)

        /** The change checks' default document cap, 8 MiB (`ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES`, held equal by a test). */
        const val DEFAULT_DOCUMENT_CAP: Long = 8L * 1024 * 1024

        /**
         * The most an index entry takes stored, its largest revision, size and timestamp and both digests written out
         * (measured by `RevisionIndexCodecTest` in `cosec-cocache`, which holds every entry under it).
         */
        const val INDEX_ENTRY_BYTES: Long = 320
    }
}
