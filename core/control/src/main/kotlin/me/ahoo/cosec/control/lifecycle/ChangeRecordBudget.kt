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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ControlStatus
import me.ahoo.cosec.control.store.RetentionPolicy

/**
 * What a document's change records may weigh (`CTL-83`, DR2-E-2), derived from what the operator already set
 * (`cosec.control.revisions.max-count`, the document cap) and nothing else:
 *
 * - **One record** is written with at most [recordCap] bytes: its operations, which the body a route reads holds
 *   ([ControlDerived.MAX_BODY_BYTES]), and what the checks derive from the candidate, a document at most the cap: its
 *   findings, its confirmations and its escalations, which the grant analysis keeps to a few hundred kilobytes
 *   (`CTL-88`). A record past it is not written ([requireWithin]).
 * - **The open records** together weigh at most [RetentionPolicy.byteBudget] when a new change is stored, as its
 *   revision records do (`CTL-84`): a new change past it is refused ([OpenLimit]).
 * - **The ended records** are bounded by the product: at most `max-count` of them, each at most [recordCap]. Measuring
 *   them would read every ended record of a document on every write that ends a change.
 */
@InternalCoSecApi
object ChangeRecordBudget {
    /** The most bytes one change record is written with: the body a route reads, and a document's worth for the checks. */
    fun recordCap(documentCap: Long): Long = ControlDerived.MAX_BODY_BYTES + documentCap

    /** What a decoded change record holds of the heap per byte it is stored with: a document's ([DocumentCache]'s) factor. */
    const val DECODED_FACTOR: Int = 4

    /**
     * What reading one change record weighs in a call's work budget (`CTL-85`, DR2-E-4): one at the cap, decoded. A call
     * that reads a document's open changes reads them one at a time, so this is all it holds for them, however many.
     */
    fun readWeight(documentCap: Long): Long = recordCap(documentCap) * DECODED_FACTOR

    /** The most bytes a document's ended change records weigh: `max-count` records at [recordCap]. */
    fun endedBytes(retention: RetentionPolicy): Long = retention.maxCount * recordCap(retention.documentCap)

    /**
     * The most one document stores: its revision records ([RetentionPolicy.byteBudget]), its open change records (as
     * much again, when a change is stored), its ended ones ([endedBytes]) and its revision index (the cap).
     */
    fun documentBytes(retention: RetentionPolicy): Long =
        2 * retention.byteBudget + endedBytes(retention) + retention.documentCap

    /** [change] encoded, as the store writes it, once it is at most [cap] bytes; otherwise a `422`, nothing written. */
    fun requireWithin(change: ChangeRequest, cap: Long): Long {
        val size = ChangeRequestCodec.encode(change).size.toLong()
        if (size > cap) {
            throw ChangeRejectedException(
                ControlStatus.UNPROCESSABLE,
                "change ${change.id.value} would be recorded with $size bytes, over the $cap a change record holds " +
                    "(the largest body and a document's worth for its checks, CTL-83): split the change",
            )
        }
        return size
    }

    /** One line for the startup log: what one tenant document's records weigh at most, and that it is per document. */
    fun describe(retention: RetentionPolicy): String {
        val mb = 1024L * 1024
        return "change records: one at most ${recordCap(retention.documentCap) / mb}MB (the largest body and a " +
            "document's worth); one document stores at most ${documentBytes(retention) / mb}MB: revisions " +
            "${retention.byteBudget / mb}MB, open changes ${retention.byteBudget / mb}MB, ended changes " +
            "${endedBytes(retention) / mb}MB (each max-count=${retention.maxCount} times), its index " +
            "${retention.documentCap / mb}MB; times the number of tenant documents"
    }
}
