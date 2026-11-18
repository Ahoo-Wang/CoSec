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
 * ISO-8601 parsing as `java.time`'s `ISO_OFFSET_DATE_TIME`, `ISO_LOCAL_DATE_TIME` and `ISO_LOCAL_TIME` do it, for
 * years 0000–9999.
 */
internal object IsoTime {
    const val SECONDS_PER_DAY = 86_400L
    const val NANOS_PER_SECOND = 1_000_000_000L
    private const val DAYS_0000_TO_1970 = 719_528L
    private const val DATE_LENGTH = 10
    private const val SHORT_TIME = "dd:dd"
    private const val TIME = "dd:dd:dd"
    private const val TIME_CHARS = "0123456789:."
    val ZONE_OFFSETS = listOf("d", "dd", "dd_dd", "dd:dd", "dd_dd_dd", "dd:dd:dd")
    val DATE_TIME_OFFSETS = listOf("dd", "dd:dd", "dd:dd:dd")

    /** `2026-12-31T00:00:00Z`, `...+08:00`, or without an offset in [zone]. Null if the text is not one. */
    fun parseDateTime(text: String, zone: ZoneOffsets): Moment? {
        if (text.length <= DATE_LENGTH || text[DATE_LENGTH] !in "Tt") {
            return null
        }
        val epochDay = parseDate(text) ?: return null
        var end = DATE_LENGTH + 1
        while (end < text.length && text[end] in TIME_CHARS) {
            end++
        }
        val nanoOfDay = parseLocalTime(text, DATE_LENGTH + 1, end) ?: return null
        val local = (epochDay * SECONDS_PER_DAY) + (nanoOfDay / NANOS_PER_SECOND)
        val nano = (nanoOfDay % NANOS_PER_SECOND).toInt()
        val epochSecond = when {
            end == text.length -> zone.epochSecondOf(local)
            end == text.length - 1 && text[end] in "Zz" -> local
            else -> local - (parseOffset(text, end, text.length, DATE_TIME_OFFSETS) ?: return null)
        }
        return Moment(epochSecond, nano)
    }

    /** `HH:mm`, `HH:mm:ss` or `HH:mm:ss.fffffffff` in `text[start, end)`, as nanoseconds of the day. */
    fun parseLocalTime(text: String, start: Int, end: Int = text.length): Long? {
        val length = end - start
        val clock = when {
            length == SHORT_TIME.length -> numbers(text, start, end, SHORT_TIME)
            length >= TIME.length -> numbers(text, start, start + TIME.length, TIME)
            else -> null
        } ?: return null
        val nano = if (length > TIME.length) fraction(text, start + TIME.length, end) ?: return null else 0L
        val hour = clock[0]
        val minute = clock[1]
        val second = clock.getOrElse(2) { 0 }
        if (hour > 23 || maxOf(minute, second) > 59) {
            return null
        }
        return (((hour * 3600L) + (minute * 60L) + second) * NANOS_PER_SECOND) + nano
    }

    /** `.f` to `.fffffffff` in `text[start, end)`, as nanoseconds. */
    private fun fraction(text: String, start: Int, end: Int): Long? {
        val digits = end - start - 1
        if (text[start] != '.' || digits !in 1..9) {
            return null
        }
        val value = numbers(text, start + 1, end, "d".repeat(digits)) ?: return null
        return value[0] * POWERS_OF_TEN[9 - digits]
    }

    /**
     * An offset in seconds, `+` or `-` followed by one of [shapes] ([numbers]). At most 18 hours.
     *
     * Zone ids ([ZONE_OFFSETS]) take more forms than the offsets of date-times ([DATE_TIME_OFFSETS]), as in `java.time`.
     */
    fun parseOffset(text: String, start: Int, end: Int, shapes: List<String>): Int? {
        if (end - start < 2 || text[start] !in "+-") {
            return null
        }
        val fields = shapes.firstNotNullOfOrNull { numbers(text, start + 1, end, it) } ?: return null
        val minutes = fields.getOrElse(1) { 0 }
        val seconds = fields.getOrElse(2) { 0 }
        val total = (fields[0] * 3600) + (minutes * 60) + seconds
        if (maxOf(minutes, seconds) > 59 || total > ZoneOffsets.MAX_OFFSET_SECONDS) {
            return null
        }
        return if (text[start] == '+') total else -total
    }

    /** `yyyy-MM-dd` at the start of [text], as days since 1970-01-01. */
    private fun parseDate(text: String): Long? {
        val (year, month, day) = numbers(text, 0, DATE_LENGTH, "dddd-dd-dd") ?: return null
        if (month !in 1..12 || day !in 1..lengthOfMonth(year, month)) {
            return null
        }
        return epochDay(year, month, day)
    }

    /**
     * Matches `text[start, end)` against [shape], in which `d` is a digit, `_` separates two numbers without a
     * character between them, and anything else stands for itself. Returns the numbers, or null if it does not match.
     */
    private fun numbers(text: String, start: Int, end: Int, shape: String): IntArray? {
        if (end - start != shape.count { it != '_' }) {
            return null
        }
        val numbers = ArrayList<Int>(3)
        var value = -1
        var i = start
        for (c in shape) {
            if (c == 'd') {
                val digit = text[i++]
                if (digit !in '0'..'9') {
                    return null
                }
                value = maxOf(value, 0) * 10 + (digit - '0')
                continue
            }
            if (value >= 0) {
                numbers += value
                value = -1
            }
            if (c != '_' && text[i++] != c) {
                return null
            }
        }
        if (value >= 0) {
            numbers += value
        }
        return numbers.toIntArray()
    }

    /** As `LocalDate.toEpochDay`, for years 0–9999. */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        var total = (365L * year) + ((year + 3) / 4) - ((year + 99) / 100) + ((year + 399) / 400)
        total += (367L * month - 362) / 12
        total += day - 1
        if (month > 2) {
            total--
            if (!isLeap(year)) {
                total--
            }
        }
        return total - DAYS_0000_TO_1970
    }

    private fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private val POWERS_OF_TEN = LongArray(10).also {
        it[0] = 1
        for (i in 1 until it.size) it[i] = it[i - 1] * 10
    }
}
