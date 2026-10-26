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

/** Checks `time` as the 7.x `TimeConditionMatcher` validates its configuration, reporting errors to [error]. */
internal class TimeChecker(
    private val path: String,
    private val limits: ConditionLimits,
    private val error: (String, String) -> Unit,
) {
    private var failed = false

    fun check(condition: Condition.Time, zones: ZoneResolver): CheckedCondition? {
        bound(condition)
        if (failed) {
            return null
        }
        val zoneId = condition.zone ?: "UTC"
        val zone = zones.resolve(zoneId)
        if (zone == null) {
            fail(".zone", "unknown time zone `$zoneId`; region zones need the host's resolver")
            return null
        }
        val notBefore = condition.notBefore?.let { instant(it, zone, ".notBefore") }
        val notAfter = condition.notAfter?.let { instant(it, zone, ".notAfter") }
        val days = condition.daysOfWeek?.let(::days) ?: DaysOfWeek.ALL
        val from = condition.from?.let { localTime(it, ".from") } ?: NO_WINDOW
        val to = condition.to?.let { localTime(it, ".to") } ?: NO_WINDOW
        if (!failed) {
            validate(condition, notBefore, notAfter, from, to)
        }
        return if (failed) null else CheckedCondition.Time(zone, notBefore, notAfter, days, from, to)
    }

    private fun validate(condition: Condition.Time, notBefore: Moment?, notAfter: Moment?, from: Long, to: Long) {
        val empty = notBefore == null && notAfter == null && condition.daysOfWeek == null && condition.from == null
        val hasFrom = from >= 0
        val hasTo = to >= 0
        when {
            empty -> fail("", "needs at least one of notBefore, notAfter, daysOfWeek or from/to")
            hasFrom != hasTo -> fail("", "needs both from and to, or neither")
            hasFrom && from == to -> fail(".to", "must differ from `from`: the window would be empty")
            notBefore != null && notAfter != null && notBefore >= notAfter ->
                fail(".notAfter", "must be after notBefore")
        }
    }

    /** The fields are literals: bounded before any is parsed, or reaches the host's zone resolver. */
    private fun bound(condition: Condition.Time) {
        length(condition.zone, ".zone")
        length(condition.notBefore, ".notBefore")
        length(condition.notAfter, ".notAfter")
        length(condition.from, ".from")
        length(condition.to, ".to")
        val days = condition.daysOfWeek ?: return
        if (days.size > limits.maxInValues) {
            fail(".daysOfWeek", "has ${days.size} values, more than ${limits.maxInValues}")
            return
        }
        days.forEachIndexed { i, day -> length(day, ".daysOfWeek[$i]") }
    }

    private fun length(text: String?, field: String) {
        val bytes = if (text == null) 0 else ConditionChecker.utf8Length(text)
        if (bytes > limits.maxLiteralBytes) {
            fail(field, "is $bytes bytes long, more than ${limits.maxLiteralBytes}")
        }
    }

    private fun instant(text: String, zone: ZoneOffsets, field: String): Moment? =
        IsoTime.parseDateTime(text, zone) ?: null.also { fail(field, "is not an ISO date-time: `$text`") }

    private fun localTime(text: String, field: String): Long? =
        IsoTime.parseLocalTime(text, 0) ?: null.also { fail(field, "is not a time of day (HH:mm[:ss]): `$text`") }

    private fun days(values: List<String>): Int {
        if (values.isEmpty()) {
            fail(".daysOfWeek", "needs at least one day")
        }
        var days = 0
        values.forEachIndexed { i, value ->
            val day = DaysOfWeek.parse(value)
            if (day == null) {
                fail(".daysOfWeek[$i]", "is not a day of the week: `$value`")
            } else {
                days = days or day
            }
        }
        return days
    }

    private fun fail(field: String, message: String) {
        failed = true
        error("$path.time$field", message)
    }

    private companion object {
        const val NO_WINDOW = -1L
    }
}
