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
import java.time.Instant

/**
 * How far [revision] of a document has propagated (`CTL-62`): of the [recent] instances, the [holding] ones report the
 * document at some revision, and the [installed] ones at [revision] or later; [unknown] are the recent ones whose
 * resident documents are missing or do not decode (`CTL-75`). [asOf] is when the resident documents were read.
 */
@InternalCoSecApi
data class Propagation(
    val revision: Long,
    val installed: Int,
    val holding: Int,
    val recent: Int,
    val unknown: Int,
    val asOf: Instant,
)
