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

/**
 * What a key's [KeyDrain] and [KeyEffects] reach of their [KeyRunner] (RFC 0013 B2b): one narrow interface the runner
 * implements, in place of a function object each (step 3, item 4). Dependencies still go one way: the drain and the
 * effects see these calls, never the runner's own members.
 */
internal interface KeyOwner<T : Any> {
    /** Raises [event] behind what is queued for this key. */
    fun offer(event: KeyEvent<T>)

    /** `Install`'s one way in: the family's counts move, then the volatile write `peek` reads. */
    fun published(previous: SnapshotEntry<T>?, entry: SnapshotEntry<T>)

    /** `Drop`: the key leaves its family; a removal that was not [claimed] moves the count itself. */
    fun dropped(claimed: Boolean)

    /** `Redispatch`: the same event, with the same waiter, to the key's current runner. */
    fun redispatched(event: KeyEvent.Requested)

    /** `DropIfEmpty`: this key's own eviction, if it holds nothing. */
    fun dropIfEmpty()

    /** Claims the family's decrement for an eviction of the kind named (`SNP-14`); asked under the flag. */
    fun claim(absent: Boolean): Boolean

    /** Gives back a claim taken for a removal whose transition threw (`SNP-14`'s accounting). */
    fun giveBack(absent: Boolean)

    /** Publishes what readers outside the drain see: removed, and idle. */
    fun hinted(removed: Boolean, idle: Boolean)
}
