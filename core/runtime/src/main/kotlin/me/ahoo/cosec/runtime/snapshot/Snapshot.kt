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
import java.time.Duration
import java.time.Instant

/**
 * The documents one evaluation reads: the shared [policies] document and, when the request needs it, one [app]
 * document. Every entry is immutable, so an evaluation sees exactly one revision of each document (RFC 0003 §8.4 P7).
 */
@InternalCoSecApi
class Snapshot<out P : Any, out A : Any> internal constructor(
    val policiesEntry: SnapshotEntry<P>,
    /** Null when the request needs no app document. */
    val appEntry: SnapshotEntry<A>?,
) {
    val policies: DocumentState<P> get() = policiesEntry.state

    val app: DocumentState<A>? get() = appEntry?.state

    /** The lag of the older of the documents (RFC 0003 §4.5). */
    fun lagAt(now: Instant): Duration {
        val policiesLag = policiesEntry.lagAt(now)
        val appLag = appEntry?.lagAt(now) ?: return policiesLag
        return maxOf(policiesLag, appLag)
    }

    override fun toString(): String = "Snapshot(policies=$policiesEntry, app=$appEntry)"
}
