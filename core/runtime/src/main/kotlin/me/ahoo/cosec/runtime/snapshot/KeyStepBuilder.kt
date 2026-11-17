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

/**
 * One transition being built: the state as it stands, the effects so far, and `families.seen[key]` as the effects so
 * far leave it, so that a load started later in the same transition chooses its read as [Slot] would, after them.
 * Local to one call of [KeyTransitions.next]: the function stays pure.
 */
internal class KeyStepBuilder<T : Any>(var state: KeyState<T>, val moment: KeyMoment) {
    var seen: Seen? = moment.seen
    private val effects = ArrayList<KeyEffect<T>>(EXPECTED_EFFECTS)

    fun emit(effect: KeyEffect<T>) {
        effects += effect
    }

    /** The next load or handle number. */
    fun serial(): Long = (state.serial + 1).also { state = state.copy(serial = it) }

    fun handle(): KeyHandle<T> = KeyHandle(serial())

    fun build(): KeyStep<T> = KeyStep(state, effects.toList())

    private companion object {
        const val EXPECTED_EFFECTS = 6
    }
}
