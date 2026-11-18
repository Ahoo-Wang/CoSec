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
import java.time.Duration

/**
 * One key's state, as [KeyTransitions] sees it (RFC 0013 B2b): an immutable value, replaced by each transition.
 * [again] is a reload asked while a load was in flight, [againForced] one asked in full.
 * `lastAccess` is not here: `touch` writes it outside any transition, and only eviction reads it.
 */
internal data class KeyState<T : Any>(
    val installed: SnapshotEntry<T>? = null,
    /** The epoch the load that installed [installed] started in. */
    val installedEpoch: Long = 0,
    /** The single load in flight. */
    val load: LoadState<T>? = null,
    /** A change, a reload or a recompile arrived while [load] was in flight: another load follows it. */
    val again: Boolean = false,
    /** The load that follows reads in full. */
    val againForced: Boolean = false,
    /** The handle of the load that follows [load], made by the first reload that needs it. */
    val next: KeyHandle<T>? = null,
    /** The periodic pass whose read the load that follows [load] is: one that met a load older than itself (`SNP-6`). */
    val againPass: Pass? = null,
    /** Evicted or dropped: no load starts, and the key's requests go to whatever slot holds the key now. */
    val removed: Boolean = false,
    /** The delay before the next retry of a reload the compile queue rejected; doubles while it keeps rejecting. */
    val retryDelay: Duration,
    /** The last load or handle number given out: an event about a load names it, and one about an older load is dropped. */
    val serial: Long = 0,
) {
    val idle: Boolean get() = load == null
}
