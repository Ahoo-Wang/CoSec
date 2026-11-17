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
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * One transition being built: the state as it stands, the effects so far, and `families.seen[key]` as the effects so
 * far leave it, so that a load started later in the same transition chooses its read as [Slot] would, after them.
 * [KeyTransitions.next] makes its own and stays pure. A runner's drain keeps one and [reset]s it for each event, under
 * its flag, executing the effects in place before the next; nothing of a step outlives it (RFC 0013 B2b step 3).
 *
 * The state is a draft of [KeyState]'s fields, written in place: a step makes one [KeyState] when it is read as a
 * whole ([state]) after a change, and none when nothing changed. A write that leaves a field as it was (the same
 * value, the same object) changes nothing.
 */
internal class KeyStepBuilder<T : Any>(state: KeyState<T>, moment: KeyMoment, key: SnapshotKey) {
    /** The key this step is for: the transitions are shared, the step is not. */
    var key: SnapshotKey = key
        private set

    private var base: KeyState<T> = state
    private var changed = false

    var installed: SnapshotEntry<T>? = state.installed
        set(value) {
            if (field !== value) changed = true
            field = value
        }
    var installedEpoch: Long = state.installedEpoch
        set(value) {
            if (field != value) changed = true
            field = value
        }
    var load: LoadState<T>? = state.load
        set(value) {
            if (field !== value) changed = true
            field = value
        }
    var again: Boolean = state.again
        set(value) {
            if (field != value) changed = true
            field = value
        }
    var againForced: Boolean = state.againForced
        set(value) {
            if (field != value) changed = true
            field = value
        }
    var next: KeyHandle<T>? = state.next
        set(value) {
            if (field !== value) changed = true
            field = value
        }
    var removed: Boolean = state.removed
        set(value) {
            if (field != value) changed = true
            field = value
        }
    var retryDelay: Duration = state.retryDelay
        set(value) {
            if (field !== value) changed = true
            field = value
        }
    var serial: Long = state.serial
        set(value) {
            if (field != value) changed = true
            field = value
        }

    var moment: KeyMoment = moment
        private set
    var seen: Seen? = moment.seen
    private val emitted = ArrayList<KeyEffect<T>>(EXPECTED_EFFECTS)

    /** The state as the step leaves it so far: the one it started from when nothing changed. */
    val state: KeyState<T>
        get() {
            if (changed) {
                base = KeyState(installed, installedEpoch, load, again, againForced, next, removed, retryDelay, serial)
                changed = false
            }
            return base
        }

    /** The effects so far, in order; read in place by the drain, valid until the next [reset]. */
    val effects: List<KeyEffect<T>> get() = emitted

    /** The next event's step: [state], [moment] and [key] as given, no effects. */
    fun reset(state: KeyState<T>, moment: KeyMoment, key: SnapshotKey) {
        this.key = key
        installed = state.installed
        installedEpoch = state.installedEpoch
        load = state.load
        again = state.again
        againForced = state.againForced
        next = state.next
        removed = state.removed
        retryDelay = state.retryDelay
        serial = state.serial
        base = state
        changed = false
        this.moment = moment
        seen = moment.seen
        emitted.clear()
    }

    fun emit(effect: KeyEffect<T>) {
        emitted += effect
    }

    /** The next load or handle number. */
    fun serial(): Long = (serial + 1).also { serial = it }

    fun handle(): KeyHandle<T> = KeyHandle(serial())

    fun build(): KeyStep<T> = KeyStep(state, emitted.toList())

    private companion object {
        const val EXPECTED_EFFECTS = 6
    }
}
