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
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal

/**
 * A translated predicate on a value 8.0 never has, written as what it is there, [Translated.Never]: an 8.0 document
 * may not write it (DOC-10, DOC-11).
 *
 * - `resource.attributes.*`: 8.0 sets none. A predicate on it reaches here only in the ALLOW direction, where
 *   [PartTranslation] has recorded the narrowing (`NOT-IN-8.0`) and the predicate never holds.
 * - A `subject.id` literal beginning with `(`: the anonymous principal, `(0)` in 7.x, has no `subject.id` in 8.0, and no
 *   signed-in principal has a reserved id (`IDN-12`). 7.x's anonymous value is the part's absent value, so
 *   [PartTranslation] adds `not exists subject.id` where 7.x's predicate held on it.
 */
internal object Unreachable {
    fun drop(translated: Translated): Translated {
        val condition = (translated as? Translated.Ast)?.condition ?: return translated
        return when (condition) {
            is Condition.Eq -> if (unreachable(condition.attribute, condition.value)) Translated.Never else translated
            is Condition.Prefix -> if (unreachable(condition.attribute, condition.value)) Translated.Never else translated
            is Condition.In -> {
                val values = condition.values.filterNot { unreachable(condition.attribute, it) }
                when {
                    values.size == condition.values.size -> translated
                    values.isEmpty() -> Translated.Never
                    else -> Translated.Ast(Condition.In(condition.attribute, values))
                }
            }

            else -> translated
        }
    }

    private fun unreachable(attribute: String, value: Any): Boolean = when {
        attribute.startsWith(Attribute.Kind.RESOURCE_ATTRIBUTE.path) -> true
        attribute == Attribute.Kind.SUBJECT_ID.path -> value is Literal.Str && Identifiers.isReserved(value.value)
        else -> false
    }
}
