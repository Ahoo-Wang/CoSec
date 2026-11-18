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
 * How each key's runner is made in a [SourceModelRun]: [KeyRunner]s draining [k] events each, whose claims the model judges
 * as they are made ([SourceModel.claimed]) and whose every event taken [onTaken] sees.
 */
internal class ModelRunners private constructor(val k: Int, private val onTaken: (KeyEvent<*>) -> Unit) {
    /** The settings for one run, its runners reporting their claims to [model]. */
    fun settings(model: SourceModel): KeyRunner.Settings =
        keyRunners(k, hooksOf = { key ->
            KeyDrain.Hooks(beforeNext = { event ->
                onTaken(event)
                // After the claim, before the transition that removes the key.
                if (event is KeyEvent.Evict && event.claim == KeyEvent.Claim.CLAIMED) model.claimed(key)
            })
        })

    companion object {
        fun runner(k: Int, onTaken: (KeyEvent<*>) -> Unit = {}) = ModelRunners(k, onTaken)
    }
}
