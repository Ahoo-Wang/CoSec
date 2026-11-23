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
package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * What the last reclamation pass of this instance found (`CTL-84`, DR1-F-6): the revision [records] the documents
 * retain, their stored [bytes] (an entry an older version wrote counts once a pass has read its record), and the
 * [openChanges]. One instance a pass holds its lease and reads every document; every other instance's last pass read
 * none, and reports [NONE]. So, summed across instances, they are the store's as of the last pass.
 */
@InternalCoSecApi
class RetainedTotals(val records: Long, val bytes: Long, val openChanges: Long) {
    override fun toString(): String = "RetainedTotals(records=$records, bytes=$bytes, openChanges=$openChanges)"

    /** A pass's running count. */
    internal class Counting {
        var records = 0L
        var bytes = 0L
        var openChanges = 0L

        fun done() = RetainedTotals(records, bytes, openChanges)
    }

    @InternalCoSecApi
    companion object {
        /** What an instance whose last pass did not hold the lease, or could not run, reports. */
        val NONE = RetainedTotals(0, 0, 0)
    }
}
