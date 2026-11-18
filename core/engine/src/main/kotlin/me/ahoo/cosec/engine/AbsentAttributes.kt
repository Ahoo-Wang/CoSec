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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress

/** A host that has no attributes of its own: every read is absent. */
@InternalCoSecApi
object AbsentAttributes : ConditionInput {
    override fun string(attribute: Attribute): String? = null

    override fun bool(attribute: Attribute): Boolean? = null

    override fun stringSet(attribute: Attribute): Set<String>? = null

    override fun ip(attribute: Attribute): IpAddress? = null

    override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME
}
