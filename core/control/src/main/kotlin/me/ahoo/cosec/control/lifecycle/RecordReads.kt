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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * What reading a document's open changes holds of a call's work (`CTL-85`, DR2-E-4): before each record is decoded, its
 * stored size times the decode factor, a record at [recordCap] when the store cannot tell. The call holds only the
 * heaviest record read so far, never their sum, since one is decoded at a time; a record whose weight is within what
 * the budget lets pass free ([WorkBudget.free]) holds nothing, as most open changes do. A read outside a call (a
 * maintenance pass) holds nothing either.
 *
 * The bytes of the record being read are in memory for a moment before they are weighed: at most one record's, of one
 * call.
 */
internal class RecordReads(private val work: WorkBudget, private val tenant: String, private val recordCap: Long) {
    /** What this call holds for its reads so far: the heaviest record's weight, or less by at most [WorkBudget.free]. */
    private var held = 0L

    /** Before a record of [size] stored bytes (`null`: not known) is decoded. */
    suspend fun before(size: Long?) {
        val weight = (size ?: recordCap) * ChangeRecordBudget.DECODED_FACTOR
        val more = weight - held
        // What passes free is not held, and a record no heavier than one held already is held already.
        if (weight <= work.free || more <= work.free) return
        work.hold(tenant, more)
        held = weight
    }
}
