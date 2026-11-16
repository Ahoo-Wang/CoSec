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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.condition.Attribute

/** What a [StatementIndex] reads from one request, and where it puts the positions it finds. */
internal interface IndexLookup {
    val action: Action
    val resourceType: String

    /** The request path decoded, or null when it does not decode: then the path cannot rule anything out. */
    fun decodedPath(): HttpPath?

    /** The request's value of an index attribute. Never calls the host. */
    fun indexValue(attribute: Attribute): String?

    /** Positions of statements that may match; sorted, and never modified by the receiver. */
    fun add(positions: IntArray)
}
