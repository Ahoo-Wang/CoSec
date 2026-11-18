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
import java.util.function.LongSupplier

/**
 * What `peek` reads and writes of one key on every request (RFC 0013 B2b): the installed entry, the removed flag and the
 * LRU stamp. They are final here, on the one base of [KeyRunner], so each stays a field access the JIT inlines; the
 * runner's call surface is its own. [quiescent] is the tests' question about the same key, asked of what the runner says.
 */
internal abstract class PeekedKey<T : Any>(val key: SnapshotKey, private val ticker: LongSupplier) {
    /** What `peek` serves; written by the runner alone. */
    @Volatile
    var installed: SnapshotEntry<T>? = null
        protected set

    /**
     * Set when the key leaves its family's map (evicted or dropped): its entry is never served again, and its requests
     * go to the key's current runner.
     */
    @Volatile
    var removed = false
        protected set

    /**
     * The LRU stamp. A plain field: every hit writes it, and eviction only needs an approximate order, so it does
     * without the memory barrier a volatile write would cost on the hot path.
     */
    var lastAccess: Long = ticker.asLong
        private set

    fun touch() {
        lastAccess = ticker.asLong
    }

    /**
     * A hint: whether no load was in flight when asked. An eviction pass uses it to choose; the removal is what checks,
     * on the true state, and refuses a key that is loading by then.
     */
    abstract fun idle(): Boolean

    /** Tests only: nothing of this key in motion: no load in flight, no event queued, no drain. */
    fun quiescent(): Boolean = idle() && !draining

    /** Tests only: an event queued or a drain under way. */
    protected abstract val draining: Boolean
}
