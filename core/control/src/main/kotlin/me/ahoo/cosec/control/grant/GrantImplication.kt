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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionContainment
import me.ahoo.cosec.model.condition.Containment

/** Whether one condition holds wherever another does: what narrowing and the verdict for a fragment share (§7.4.3). */
internal object GrantImplication {
    /**
     * Whether [outer] (the condition of a statement one of whose resources contains the inner one), specialized, holds
     * on every request that matches the inner resource and satisfies [condition].
     */
    fun implied(
        condition: CheckedCondition?,
        outer: Condition?,
        specialization: () -> Specialization,
    ): Boolean {
        if (outer == null) {
            return true
        }
        // A statement holds under any of its matching resources' captures (ALG-10): resource [index] contains [inner],
        // so it matches every request [inner] does, and the condition read under its captures is enough.
        val specialized = specialization()
        return when (val folded = specialized.fold(outer)) {
            Folded.True -> true
            Folded.False, is Folded.Incomparable -> false
            is Folded.Residual -> {
                val checked = specialized.check(folded.condition) ?: return false
                condition != null && ConditionContainment.implies(condition, checked) == Containment.YES
            }
        }
    }
}
