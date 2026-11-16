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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.time.Instant

/**
 * The documents one evaluation reads from a source with several families: the [shared] document and the [scoped] ones
 * it asked for, in that order. Every entry is immutable, so the evaluation sees one revision of each (RFC 0003 §8.4 P7).
 */
@InternalCoSecApi
class SnapshotSet<out P : Any, out A : Any> internal constructor(
    val shared: SnapshotEntry<P>,
    val scoped: List<SnapshotEntry<A>>,
) {
    /** The entry of [key], or null when this set does not hold it. */
    fun entry(
        key: SnapshotKey
    ): SnapshotEntry<*>? = if (shared.key == key) shared else scoped.firstOrNull { it.key == key }

    /** The lag of the oldest document (RFC 0003 §4.5). */
    fun lagAt(now: Instant): Duration = scoped.fold(shared.lagAt(now)) { lag, entry -> maxOf(lag, entry.lagAt(now)) }

    override fun toString(): String = "SnapshotSet(shared=$shared, scoped=$scoped)"
}
