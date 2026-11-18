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
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * What makes the [KeySlot] of each key of a [SnapshotSource] (RFC 0013 B2b): [RUNNER], the [KeyRunner], in production
 * since step 3. [SLOT], the monitor-based [Slot], is kept for the tests that compare the two until step 4 removes it;
 * only tests choose a factory, through the source's internal constructor.
 */
internal interface SlotFactory {
    fun <T : Any> create(key: SnapshotKey, compiler: SnapshotCompiler<T>, context: SlotContext): KeySlot<T>

    companion object {
        /** The runner: one key's transitions through a queue and a drain (RFC 0013 B2b step 3). */
        val RUNNER: SlotFactory = object : SlotFactory {
            override fun <T : Any> create(
                key: SnapshotKey,
                compiler: SnapshotCompiler<T>,
                context: SlotContext
            ): KeySlot<T> =
                KeyRunner(key, compiler, context)
        }

        /** The monitor-based slot, until step 4: for the tests that run both. */
        val SLOT: SlotFactory = object : SlotFactory {
            override fun <T : Any> create(
                key: SnapshotKey,
                compiler: SnapshotCompiler<T>,
                context: SlotContext
            ): KeySlot<T> =
                Slot(key, compiler, context)
        }
    }
}
