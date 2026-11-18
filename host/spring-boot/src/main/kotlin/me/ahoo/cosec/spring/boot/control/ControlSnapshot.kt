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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import java.time.Clock

/** The control plane's own snapshot source, held apart from the data plane's (see the bean's comment). */
internal class ControlSnapshot(
    val source: SnapshotSource<CompiledDocument, CompiledDocument>,
    /** The clock [source] dates its entries with; confirmation compares against it. */
    val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    override fun close() = source.close()
}
