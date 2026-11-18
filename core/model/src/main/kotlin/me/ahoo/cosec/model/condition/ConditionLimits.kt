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

package me.ahoo.cosec.model.condition

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * The cost bounds of a condition (RFC 0005 §3.5, Q9). The defaults are the values decided in Q9. The platform
 * document's `limits` section sets them for every document (RFC 0007 §3.1 N3), so the control plane and every
 * instance compile against the same bounds.
 */
@InternalCoSecApi
class ConditionLimits(
    val maxNodes: Int = 64,
    val maxDepth: Int = 8,
    val maxInValues: Int = 1024,
    val maxCidrBlocks: Int = 256,
    /** In UTF-8 bytes. */
    val maxLiteralBytes: Int = 1024,
    /** The sum of the node weights ([CheckedCondition.Weights]). */
    val maxCost: Int = 256,
) {
    init {
        val limits = intArrayOf(maxNodes, maxDepth, maxInValues, maxCidrBlocks, maxLiteralBytes, maxCost)
        require(limits.all { it > 0 }) { "Condition limits must be positive." }
    }

    @InternalCoSecApi
    companion object {
        val DEFAULT: ConditionLimits = ConditionLimits()
    }
}
