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

/** The days of a week, Monday first, as documents write them. */
internal object DaysOfWeek {
    val NAMES: List<String> = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    const val ALL: Int = 0b111_1111

    /** The bit of the day [value] names, or null: only the exact [NAMES] are days, as the schema says. */
    fun parse(value: String): Int? {
        val index = NAMES.indexOf(value)
        return if (index < 0) null else 1 shl index
    }

    /** The day of [epochDay] (days since 1970-01-01, a Thursday), Monday = 0. */
    fun of(epochDay: Long): Int = Math.floorMod(epochDay + 3, 7L).toInt()
}
