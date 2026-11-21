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
 * A change's shadow window (`shadow`, `wiki/policies/testing.md`): [window], its id, since [startedAt]; [closed] once it
 * has; [acknowledged], the number of differences a publish acknowledged.
 */
@ExperimentalCoSecApi
class Shadow(val window: String, val startedAt: Instant, val closed: ShadowClosed? = null, val acknowledged: Long? = null) {
    /** What a publish acknowledges of this window: its id and the counts it closed with; null while it is open. */
    fun ack(): ShadowAck? = closed?.let { ShadowAck(window, it.counts) }

    override fun equals(other: Any?): Boolean = other is Shadow && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Shadow",
        "window" to window,
        "startedAt" to startedAt,
        "closed" to closed,
        "acknowledged" to acknowledged,
    )

    private fun parts(): List<Any?> = listOf(window, startedAt, closed, acknowledged)
}

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
