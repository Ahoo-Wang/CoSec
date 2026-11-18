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

/** What [Revisions.verifyIndex] found comparing a document's revision index with its head's chain (`CTL-35`). */
@InternalCoSecApi
sealed interface IndexCheck {
    /** The index is the head's chain: its last entry is the head's, and each entry names the one below as its parent. */
    @InternalCoSecApi
    data object Follows : IndexCheck

    /**
     * The index could not be checked: [entries] of its entries carry no `parent`, which an older version wrote. The
     * first read of the document rewrites them.
     */
    @InternalCoSecApi
    class Unchecked(val entries: Int) : IndexCheck

    /**
     * The index ends exactly at the head's parent, and its links hold: one revision behind the head, which is what a
     * publication leaves between its head write and its index write, or a publication whose index write failed. Not a
     * finding: the next read of the history adds the head's entry.
     */
    @InternalCoSecApi
    class Behind(val revision: Long) : IndexCheck

    /** The index is behind the head by more than a publication in flight, or on another branch (a failover, a partial restore): [why]. A read repairs it. */
    @InternalCoSecApi
    class Differs(val why: String) : IndexCheck
}
