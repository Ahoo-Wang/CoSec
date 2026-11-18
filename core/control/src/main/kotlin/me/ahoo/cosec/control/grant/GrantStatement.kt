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
import me.ahoo.cosec.engine.OnMissing
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Condition

/**
 * A statement as the grant analysis reads it (RFC 0005 §5.1): its action and resource patterns and its condition as
 * written. The analysis works on the written condition because it substitutes the grantor's attributes into it before
 * checking it again (RFC 0007 §7.4.3).
 */
@InternalCoSecApi
class GrantStatement(
    val effect: Effect,
    val actions: List<ActionPattern>,
    /** A statement without a resource matches every resource: [ResourcePattern.AnyResource]. */
    val resources: List<ResourcePattern> = listOf(ResourcePattern.AnyResource),
    val condition: Condition? = null,
    val name: String = "",
    /** What an external attribute its condition reads that cannot be fetched means; `null` for the default. */
    val onMissing: OnMissing? = null,
) {
    init {
        require(actions.isNotEmpty()) { "A statement names at least one action." }
        require(resources.isNotEmpty()) { "A statement names at least one resource; AnyResource for every resource." }
    }
}
