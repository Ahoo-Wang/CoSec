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

/** An instant with nanosecond precision, as `java.time.Instant` keeps it. */
internal class Moment(val epochSecond: Long, val nano: Int) : Comparable<Moment> {
    override fun compareTo(other: Moment): Int =
        if (epochSecond != other.epochSecond) epochSecond.compareTo(other.epochSecond) else nano.compareTo(other.nano)

    override fun equals(other: Any?): Boolean = other is Moment && other.epochSecond == epochSecond && other.nano == nano

    override fun hashCode(): Int = 31 * epochSecond.hashCode() + nano

    /** The first whole millisecond at or after this moment. */
    fun ceilMillis(): Long {
        val millis = (nano + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI
        return (epochSecond * ZoneOffsets.MILLIS_PER_SECOND) + millis
    }

    companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}
