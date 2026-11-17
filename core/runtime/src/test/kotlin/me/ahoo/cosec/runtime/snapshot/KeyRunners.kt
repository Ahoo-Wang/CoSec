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
import me.ahoo.cosec.runtime.snapshot.KeyRunner.Companion.PROVISIONAL_K
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * A [SlotFactory] of [KeyRunner]s, so a source's tests run over the runner as they run over [Slot] (RFC 0013 B2b):
 * each drain takes [k] events, and [hooks] reach only the keys [hooked] accepts.
 */
internal fun keyRunners(
    k: Int = PROVISIONAL_K,
    hooks: KeyDrain.Hooks = KeyDrain.Hooks.NONE,
    hooked: (SnapshotKey) -> Boolean = { true },
): SlotFactory = object : SlotFactory {
    override fun <T : Any> create(key: SnapshotKey, compiler: SnapshotCompiler<T>, context: SlotContext): KeySlot<T> =
        KeyRunner(key, compiler, context, k, if (hooked(key)) hooks else KeyDrain.Hooks.NONE)
}
