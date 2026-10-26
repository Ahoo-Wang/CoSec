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

package me.ahoo.cosec.model.condition

/**
 * The UTC offsets of one time zone, as a table: [offsets]`[i]` (seconds) holds from `transitions[i - 1]` (inclusive,
 * epoch seconds) to `transitions[i]`. A transition starts its new offset at its instant, as `java.time` does.
 *
 * `model/` may not read the JVM's time-zone database (`EnginePurityTest`), so `time` conditions in a region zone
 * (`Asia/Shanghai`) take this table from a [ZoneResolver] the host provides; the table must cover every instant the
 * policies are evaluated at. Fixed offsets need no host ([ZoneResolver.FIXED]).
 */
class ZoneOffsets(val id: String, transitions: LongArray, offsets: IntArray) {
    private val transitions: LongArray = transitions.copyOf()
    private val offsets: IntArray = offsets.copyOf()

    init {
        require(offsets.size == transitions.size + 1) { "Zone $id needs one offset more than it has transitions." }
        for (i in 1 until transitions.size) {
            require(transitions[i - 1] < transitions[i]) { "Zone $id has transitions out of order." }
        }
        require(
            offsets.all { it in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS }
        ) { "Zone $id has an offset beyond 18 hours." }
    }

    /** The offset in seconds at [epochSecond]. */
    fun offsetSecondsAt(epochSecond: Long): Int = offsets[indexAt(epochSecond)]

    internal fun offsetMillisAt(epochMillis: Long): Long =
        offsetSecondsAt(Math.floorDiv(epochMillis, MILLIS_PER_SECOND)) * MILLIS_PER_SECOND

    /**
     * The instant (epoch seconds) of a local date-time, given as seconds since `1970-01-01T00:00` local time. Like
     * `LocalDateTime.atZone`: in an overlap the earlier offset wins; in a gap the local time moves forward by the
     * length of the gap.
     */
    fun epochSecondOf(localEpochSecond: Long): Long {
        val first = indexAt(localEpochSecond - MAX_OFFSET_SECONDS)
        val last = indexAt(localEpochSecond + MAX_OFFSET_SECONDS)
        for (i in first..last) {
            val instant = localEpochSecond - offsets[i]
            if (indexAt(instant) == i) {
                return instant
            }
        }
        for (i in first + 1..last) {
            val before = offsets[i - 1]
            if (localEpochSecond >= transitions[i - 1] + before && localEpochSecond < transitions[i - 1] + offsets[i]) {
                return localEpochSecond - before
            }
        }
        return localEpochSecond - offsets[indexAt(localEpochSecond)]
    }

    /** The number of transitions at or before [epochSecond]: the index of the offset in force. */
    private fun indexAt(epochSecond: Long): Int {
        var low = 0
        var high = transitions.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (transitions[mid] <= epochSecond) low = mid + 1 else high = mid
        }
        return low
    }

    override fun equals(other: Any?): Boolean = other is ZoneOffsets && other.id == id &&
        other.transitions.contentEquals(transitions) && other.offsets.contentEquals(offsets)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        const val MAX_OFFSET_SECONDS: Int = 18 * 3600
        internal const val MILLIS_PER_SECOND = 1000L

        fun fixed(id: String, offsetSeconds: Int): ZoneOffsets = ZoneOffsets(
            id,
            LongArray(0),
            intArrayOf(offsetSeconds)
        )
    }
}

/** Finds the offsets of a time zone by id, or returns null if it does not know the zone. */
fun interface ZoneResolver {
    fun resolve(id: String): ZoneOffsets?

    companion object {
        /**
         * The zones `java.time.ZoneId.of` reads without its database: `Z`, `UTC`, `GMT`, `UT`, offsets such as `+8`,
         * `+08`, `+0830`, `+08:30`, `+08:30:15`, and those offsets after `UTC`, `GMT` or `UT`.
         */
        val FIXED: ZoneResolver = ZoneResolver(::fixedOffset)

        private val UTC_IDS = setOf("Z", "UTC", "GMT", "UT")

        /** [FIXED], then [regions] for the ids it does not know. */
        fun withRegions(regions: ZoneResolver): ZoneResolver = ZoneResolver { fixedOffset(it) ?: regions.resolve(it) }

        private fun fixedOffset(id: String): ZoneOffsets? {
            if (id in UTC_IDS) {
                return ZoneOffsets.fixed(id, 0)
            }
            val offsetText = when {
                id.startsWith("UTC") || id.startsWith("GMT") -> id.substring(3)
                id.startsWith("UT") -> id.substring(2)
                else -> id
            }
            val seconds = IsoTime.parseOffset(offsetText, 0, offsetText.length, IsoTime.ZONE_OFFSETS) ?: return null
            return ZoneOffsets.fixed(id, seconds)
        }
    }
}

/** An instant with nanosecond precision, as `java.time.Instant` keeps it. */
internal class Moment(val epochSecond: Long, val nano: Int) : Comparable<Moment> {
    override fun compareTo(other: Moment): Int =
        if (epochSecond != other.epochSecond) epochSecond.compareTo(other.epochSecond) else nano.compareTo(other.nano)

    override fun equals(other: Any?): Boolean = other is Moment && other.epochSecond == epochSecond && other.nano == nano

    override fun hashCode(): Int = 31 * epochSecond.hashCode() + nano

    /** The first whole millisecond at or after this moment. */
    fun ceilMillis(): Long {
        val millis = (nano + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI
        return epochSecond * ZoneOffsets.MILLIS_PER_SECOND + millis
    }

    companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}

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
        val local = epochDay * SECONDS_PER_DAY + nanoOfDay / NANOS_PER_SECOND
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
        return (hour * 3600L + minute * 60L + second) * NANOS_PER_SECOND + nano
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
        val total = fields[0] * 3600 + minutes * 60 + seconds
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
        var total = 365L * year + (year + 3) / 4 - (year + 99) / 100 + (year + 399) / 400
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

/** The days of a week, Monday first, as `java.time.DayOfWeek` names them. */
internal object DaysOfWeek {
    val NAMES: List<String> = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
    const val ALL: Int = 0b111_1111

    /** As 7.x: trimmed, case-insensitive, full name or its first three letters. The day's bit, or null. */
    fun parse(value: String): Int? {
        val upper = Ascii.uppercase(value.trim())
        val index = NAMES.indexOfFirst { it == upper || it.take(3) == upper }
        return if (index < 0) null else 1 shl index
    }

    /** The day of [epochDay] (days since 1970-01-01, a Thursday), Monday = 0. */
    fun of(epochDay: Long): Int = Math.floorMod(epochDay + 3, 7L).toInt()
}
