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
 * The reports of a window summed over the instances (RFC 0007 §6.4): counts by outcome (`SAME`, `WIDENED`, ...), when
 * it closed, whether it closed for lack of samples ([insufficient]), and whether an instance could not compile or
 * evaluate the candidate ([blocked]): such a change never publishes.
 */
data class ShadowSummary(
    val counts: Map<String, Long>,
    val closedAt: Instant,
    val insufficient: Boolean,
    val blocked: Boolean
) {
    /** Whether publishing needs the publisher to acknowledge these numbers: anything changed, or too few samples. */
    val needsAck: Boolean get() = insufficient || ACKNOWLEDGED.any { (counts[it] ?: 0) > 0 }

    private companion object {
        val ACKNOWLEDGED = listOf("WIDENED", "NARROWED", "BECAME_INDETERMINATE", "BECAME_DENIED", "OBLIGATIONS_CHANGED")
    }
}
