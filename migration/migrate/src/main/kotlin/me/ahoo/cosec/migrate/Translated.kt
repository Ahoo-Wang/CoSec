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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.Condition

/** A translated condition: one that always holds, one that never does, or an AST. The AST has neither constant. */
internal sealed interface Translated {
    data object Always : Translated

    data object Never : Translated

    class Ast(val condition: Condition) : Translated

    companion object {
        fun and(items: List<Translated>): Translated {
            if (items.any { it == Never }) return Never
            val conditions = items.filterIsInstance<Ast>().map { it.condition }
            return when (conditions.size) {
                0 -> Always
                1 -> Ast(conditions.single())
                else -> Ast(Condition.And(conditions))
            }
        }

        fun or(items: List<Translated>): Translated {
            if (items.any { it == Always }) return Always
            val conditions = items.filterIsInstance<Ast>().map { it.condition }
            return when (conditions.size) {
                0 -> Never
                1 -> Ast(conditions.single())
                else -> Ast(Condition.Or(conditions))
            }
        }

        fun not(item: Translated): Translated = when (item) {
            Always -> Never
            Never -> Always
            is Ast -> Ast((item.condition as? Condition.Not)?.condition ?: Condition.Not(item.condition))
        }
    }
}
