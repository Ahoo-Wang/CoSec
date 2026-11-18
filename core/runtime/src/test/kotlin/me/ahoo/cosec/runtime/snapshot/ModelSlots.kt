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
 * What makes each key's slot in a [SlotModelRun]: [Slot], or [KeyRunner]s draining [k] events each, whose claims the
 * model judges as they are made ([SlotModel.claimed]) and whose every event taken [onTaken] sees.
 */
internal class ModelSlots private constructor(val k: Int?, private val onTaken: (KeyEvent<*>) -> Unit) {
    /** The factory for one run, its runners reporting their claims to [model]. */
    fun factory(model: SlotModel): SlotFactory {
        val k = k ?: return SlotFactory.SLOT
        return keyRunners(k, hooksOf = { key ->
            KeyDrain.Hooks(beforeNext = { event ->
                onTaken(event)
                // After the claim, before the transition that removes the key.
                if (event is KeyEvent.Evict && event.claim == KeyEvent.Claim.CLAIMED) model.claimed(key)
            })
        })
    }

    companion object {
        val SLOT = ModelSlots(null) {}

        fun runner(k: Int, onTaken: (KeyEvent<*>) -> Unit = {}) = ModelSlots(k, onTaken)
    }
}
