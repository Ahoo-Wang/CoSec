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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * Reads which live instances acknowledge the open shadow window of a change (`CTL-10`), for its answer: null when the
 * change has no open window, or when no statuses are read.
 */
@InternalCoSecApi
fun interface AcknowledgementReader {
    suspend fun of(change: ChangeRequest): Acknowledgement?

    @InternalCoSecApi
    companion object {
        /** Reads nothing: an answer without acknowledgements. */
        val NONE: AcknowledgementReader = AcknowledgementReader { null }
    }
}
