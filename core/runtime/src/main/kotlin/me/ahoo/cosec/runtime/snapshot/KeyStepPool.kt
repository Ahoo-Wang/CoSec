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
import java.time.Duration
import java.time.Instant

/**
 * The step builders a thread's drains reuse (RFC 0013 B2b step 3): one per depth of drains nested on the thread, since
 * an effect of one step can drain another key in place (a redispatch, an eviction's victims) while the outer step's
 * effects are still being executed. Kept per thread rather than per key, so a held key retains none; released after
 * each step, so none retains a key's state or effects.
 */
internal object KeyStepPool {
    @PublishedApi
    internal val placeholderState = KeyState<Any>(retryDelay = Duration.ZERO)

    @PublishedApi
    internal val placeholderMoment = KeyMoment(Instant.EPOCH, 0, 0, false, null)

    @PublishedApi
    internal val pools: ThreadLocal<Pool> = ThreadLocal.withInitial { Pool() }

    /** A thread's builders, by depth, and the depth its drains are at. */
    @PublishedApi
    internal class Pool {
        val builders = ArrayList<KeyStepBuilder<Any>>(2)
        var depth = 0
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <T : Any> withStep(block: (KeyStepBuilder<T>) -> Unit) {
        val pool = pools.get()
        val depth = pool.depth
        if (depth == pool.builders.size) {
            pool.builders += KeyStepBuilder(placeholderState, placeholderMoment, SnapshotKey.Platform)
        }
        val step = pool.builders[depth]
        pool.depth = depth + 1
        try {
            block(step as KeyStepBuilder<T>)
        } finally {
            step.reset(placeholderState, placeholderMoment, SnapshotKey.Platform)
            pool.depth = depth
        }
    }
}
