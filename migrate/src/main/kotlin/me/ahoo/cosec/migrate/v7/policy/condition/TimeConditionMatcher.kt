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

package me.ahoo.cosec.migrate.v7.policy.condition

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.migrate.v7.api.configuration.Configuration
import me.ahoo.cosec.migrate.v7.api.policy.ConditionMatcher
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

internal const val TIME_CONDITION_MATCHER_NOT_BEFORE_KEY = "notBefore"
internal const val TIME_CONDITION_MATCHER_NOT_AFTER_KEY = "notAfter"
internal const val TIME_CONDITION_MATCHER_ZONE_KEY = "zone"
internal const val TIME_CONDITION_MATCHER_DAYS_OF_WEEK_KEY = "daysOfWeek"
internal const val TIME_CONDITION_MATCHER_FROM_KEY = "from"
internal const val TIME_CONDITION_MATCHER_TO_KEY = "to"

/**
 * Matches while the current time is inside a validity period and, optionally, a recurring daily window.
 *
 * - `notBefore` / `notAfter`: the period, as ISO date-times; `notAfter` is exclusive. A date-time without an offset
 *   is read in `zone`. Grants that should expire on their own put `notAfter` on the statement.
 * - `daysOfWeek`: e.g. `["MONDAY", "FRIDAY"]` (three-letter forms such as `MON` work too).
 * - `from` / `to`: a daily window `[from, to)` as `HH:mm`. When `from` is after `to` the window crosses midnight,
 *   and its early-morning part belongs to the day it started on, so `daysOfWeek` checks that day.
 * - `zone`: the time zone of the window and of offset-less date-times, default `UTC`.
 *
 * The time is read from the clock only, never from the request, so a caller cannot move it.
 */
internal class TimeConditionMatcher internal constructor(
    configuration: Configuration,
    private val clock: Clock
) : AbstractConditionMatcher(TimeConditionMatcherFactory.TYPE, configuration) {

    constructor(configuration: Configuration) : this(configuration, Clock.systemUTC())

    private val zone: ZoneId = configuration.get(TIME_CONDITION_MATCHER_ZONE_KEY)?.asString()
        ?.let { ZoneId.of(it) } ?: ZoneId.of("UTC")
    private val notBefore: Instant? = instantOf(TIME_CONDITION_MATCHER_NOT_BEFORE_KEY)
    private val notAfter: Instant? = instantOf(TIME_CONDITION_MATCHER_NOT_AFTER_KEY)
    private val daysOfWeek: Set<DayOfWeek>? = configuration.get(TIME_CONDITION_MATCHER_DAYS_OF_WEEK_KEY)
        ?.asStringList()?.map(::dayOf)?.toSet()
    private val from: LocalTime? = localTimeOf(TIME_CONDITION_MATCHER_FROM_KEY)
    private val to: LocalTime? = localTimeOf(TIME_CONDITION_MATCHER_TO_KEY)

    init {
        require(notBefore != null || notAfter != null || daysOfWeek != null || from != null) {
            "A time condition needs at least one of notBefore, notAfter, daysOfWeek or from/to."
        }
        require((from == null) == (to == null)) { "A time condition needs both from and to, or neither." }
        require(from == null || from != to) { "A time condition window must not be empty: from equals to." }
        if (notBefore != null && notAfter != null) {
            require(notBefore.isBefore(notAfter)) { "A time condition needs notBefore before notAfter." }
        }
        require(daysOfWeek == null || daysOfWeek.isNotEmpty()) { "A time condition needs at least one day of week." }
    }

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val now = clock.instant()
        if (notBefore != null && now.isBefore(notBefore)) {
            return false
        }
        if (notAfter != null && !now.isBefore(notAfter)) {
            return false
        }
        val local = LocalDateTime.ofInstant(now, zone)
        var day = local.dayOfWeek
        if (from != null && to != null) {
            val time = local.toLocalTime()
            val crossesMidnight = from.isAfter(to)
            val inWindow = if (crossesMidnight) !time.isBefore(from) || time.isBefore(to) else !time.isBefore(from) && time.isBefore(to)
            if (!inWindow) {
                return false
            }
            if (crossesMidnight && time.isBefore(to)) {
                day = day.minus(1)
            }
        }
        return daysOfWeek == null || day in daysOfWeek
    }

    private fun instantOf(key: String): Instant? {
        val value = configuration.get(key)?.asString() ?: return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeParseException) {
            LocalDateTime.parse(value).atZone(zone).toInstant()
        }
    }

    private fun localTimeOf(key: String): LocalTime? = configuration.get(key)?.asString()?.let(LocalTime::parse)

    private fun dayOf(value: String): DayOfWeek {
        val upper = value.trim().uppercase()
        return DayOfWeek.entries.firstOrNull { it.name == upper || it.name.take(3) == upper }
            ?: throw IllegalArgumentException("Unknown day of week [$value].")
    }
}

internal class TimeConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "time"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher = TimeConditionMatcher(configuration)
}
