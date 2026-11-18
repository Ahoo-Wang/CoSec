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

/** The state of one document in a [SnapshotSource] (RFC 0003 §3.4). */
@InternalCoSecApi
sealed interface DocumentState<out T : Any> {
    @InternalCoSecApi
    data class Present<out T : Any>(val compiled: T) : DocumentState<T>

    /** No document is stored, and none has been seen under this key since the source started. */
    @InternalCoSecApi
    data object Absent : DocumentState<Nothing>

    /** The document cannot be used. Every layer it feeds is unavailable, so evaluation reaching it fails closed. */
    @InternalCoSecApi
    data class Failed(val error: Throwable) : DocumentState<Nothing>
}
