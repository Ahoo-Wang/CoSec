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

package me.ahoo.cosec.document.json

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The stored records this process refused by [StoredFormat]'s rule (`OBS-12`), by [StoredRecord] and [Reason]: the meter
 * `cosec.stored.refused` with `cosec-micrometer`. A record a later version wrote ([Reason.LATER]) tells an older instance
 * apart from a store that is broken ([Reason.MALFORMED]): during a rolling upgrade the first is expected, and ends when
 * every instance runs the later version (RFC 0014 G3). One count per process, whichever store or caller read the record.
 */
@InternalCoSecApi
object StoredRefusals {
    /** Why a record was refused: the closed set of the meter's `reason` tag. */
    @InternalCoSecApi
    enum class Reason(val tag: String) {
        /** A later version wrote it: a later format, or a member its `crit` names that this version does not know. */
        LATER("later"),

        /** It is not a record of its kind: no object, a format that is no positive integer, a `crit` that is not one. */
        MALFORMED("malformed"),
    }

    private val counts = AtomicLongArray(StoredRecord.entries.size * Reason.entries.size)

    /** How many [record]s this process refused for [reason] since it started. */
    fun count(record: StoredRecord, reason: Reason): Long = counts.get(slot(record, reason))

    internal fun record(record: StoredRecord, reason: Reason) {
        counts.incrementAndGet(slot(record, reason))
    }

    private fun slot(record: StoredRecord, reason: Reason) = record.ordinal * Reason.entries.size + reason.ordinal
}
