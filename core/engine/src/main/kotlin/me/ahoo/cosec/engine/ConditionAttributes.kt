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
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition

/** The attributes a condition reads. */
internal object ConditionAttributes {
    fun of(condition: CheckedCondition): List<Attribute> = ArrayList<Attribute>().also { collect(condition, it) }

    private fun collect(condition: CheckedCondition, into: MutableList<Attribute>) {
        when (condition) {
            is CheckedCondition.And -> condition.conditions.forEach { collect(it, into) }
            is CheckedCondition.Or -> condition.conditions.forEach { collect(it, into) }
            is CheckedCondition.Not -> collect(condition.condition, into)
            is CheckedCondition.AttributeEq -> {
                into += condition.left
                into += condition.right
            }

            else -> into += leaf(condition)
        }
    }

    private fun leaf(condition: CheckedCondition): Attribute =
        when (condition) {
            is CheckedCondition.Eq -> condition.attribute
            is CheckedCondition.BoolEq -> condition.attribute
            is CheckedCondition.IpEq -> condition.attribute
            is CheckedCondition.In -> condition.attribute
            is CheckedCondition.Contains -> condition.attribute
            is CheckedCondition.Prefix -> condition.attribute
            is CheckedCondition.LabelsPrefix -> condition.attribute
            is CheckedCondition.Cidr -> condition.attribute
            is CheckedCondition.Exists -> condition.attribute
            is CheckedCondition.Time -> TIME
            else -> throw IllegalArgumentException("Not a leaf: $condition")
        }

    private val TIME = Attribute.of(Attribute.Kind.ENVIRONMENT_TIME)
}
