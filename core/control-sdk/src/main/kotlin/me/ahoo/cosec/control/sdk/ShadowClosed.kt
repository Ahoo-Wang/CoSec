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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import java.time.Instant

/**
 * How a [Shadow] window closed: when, the count of each outcome it saw ([counts], by the `shadowOutcome` closed set),
 * whether it saw too little to judge ([insufficient]) and whether what it saw blocks a publish ([blocked]).
 */
@ExperimentalCoSecApi
class ShadowClosed(
    val closedAt: Instant,
    val counts: Map<String, Long>,
    val insufficient: Boolean,
    val blocked: Boolean,
    val vanished: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = other is ShadowClosed && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "ShadowClosed",
        "closedAt" to closedAt,
        "counts" to counts,
        "insufficient" to insufficient,
        "blocked" to blocked,
        "vanished" to vanished,
    )

    private fun parts(): List<Any?> = listOf(closedAt, counts, insufficient, blocked, vanished)
}
