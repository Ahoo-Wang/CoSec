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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.random.Random

/** `time` and its parts, against `java.time` and the logic of the 7.x `TimeConditionMatcher`. */
class TimeTest {
    private val zones = listOf(
        "UTC", "Asia/Shanghai", "Europe/London", "America/New_York", "Australia/Lord_Howe", "Asia/Kathmandu",
        "America/St_Johns", "Pacific/Apia", "Europe/Moscow", "+05:30", "-03", "GMT+8",
    )
    private val minMillis = Instant.parse("1990-01-01T00:00:00Z").toEpochMilli()
    private val maxMillis = Instant.parse("2090-01-01T00:00:00Z").toEpochMilli()

    @Test
    fun `offsets agree with java time`() {
        val random = Random(1)
        for (id in zones) {
            val table = requireNotNull(JavaZones.resolve(id))
            val rules = ZoneId.of(id).rules
            repeat(20_000) {
                val second = random.nextLong(minMillis, maxMillis) / 1000
                table.offsetSecondsAt(second).assert().describedAs("$id at $second")
                    .isEqualTo(rules.getOffset(Instant.ofEpochSecond(second)).totalSeconds)
            }
        }
    }

    @Test
    fun `local date-times resolve gaps and overlaps as java time does`() {
        for (id in zones) {
            val table = requireNotNull(JavaZones.resolve(id))
            val zone = ZoneId.of(id)
            val transitions = zone.rules.transitions.filter { it.dateTimeBefore.year in 2000..2030 }
            val samples = transitions.flatMap { t -> (-12..12).map { t.dateTimeBefore.plusMinutes(it * 10L) } } +
                List(2_000) { LocalDateTime.ofEpochSecond(Random(it).nextLong(minMillis, maxMillis) / 1000, 0, ZoneOffset.UTC) }
            for (local in samples) {
                table.epochSecondOf(local.toEpochSecond(ZoneOffset.UTC)).assert().describedAs("$id $local")
                    .isEqualTo(local.atZone(zone).toEpochSecond())
            }
        }
    }

    @Test
    fun `fixed zones need no database`() {
        mapOf(
            "Z" to 0, "UTC" to 0, "GMT" to 0, "UT" to 0, "+8" to 28_800, "+08" to 28_800, "-0830" to -30_600, "+08:30" to 30_600,
            "+083015" to 30_615, "+08:30:15" to 30_615, "UTC+8" to 28_800, "GMT-02:00" to -7_200, "UT+01" to 3_600, "+18:00" to 64_800
        )
            .forEach { (id, seconds) ->
                val zone = ZoneResolver.FIXED.resolve(id)
                zone?.offsetSecondsAt(0).assert().describedAs(id).isEqualTo(seconds)
                ZoneId.of(id).rules.getOffset(Instant.EPOCH).totalSeconds.assert().describedAs(id).isEqualTo(seconds)
                zone?.id.assert().isEqualTo(id)
            }
        listOf("Asia/Shanghai", "+19", "+18:01", "+8:00", "+08:60", "08:00", "UTC+", "+08:", "+08:00:", "+08:0:00", "+08x00", "")
            .forEach { ZoneResolver.FIXED.resolve(it).assert().describedAs(it).isNull() }
        ZoneResolver.withRegions(JavaZones).resolve("Asia/Shanghai")?.offsetSecondsAt(0).assert().isEqualTo(28_800)
        ZoneResolver.withRegions { null }.resolve("+01")?.offsetSecondsAt(0).assert().isEqualTo(3_600)
        ZoneResolver.withRegions { null }.resolve("Asia/Shanghai").assert().isNull()
    }

    @Test
    fun `zone tables must be well formed`() {
        runCatching { ZoneOffsets("x", longArrayOf(1), intArrayOf(0)) }.isFailure.assert().isTrue()
        runCatching { ZoneOffsets("x", longArrayOf(2, 1), intArrayOf(0, 0, 0)) }.isFailure.assert().isTrue()
        runCatching { ZoneOffsets("x", longArrayOf(), intArrayOf(18 * 3600 + 1)) }.isFailure.assert().isTrue()
        val zone = ZoneOffsets("x", longArrayOf(10), intArrayOf(0, 3600))
        zone.assert().isEqualTo(ZoneOffsets("x", longArrayOf(10), intArrayOf(0, 3600)))
        zone.assert().isNotEqualTo(ZoneOffsets("x", longArrayOf(10), intArrayOf(0, 7200)))
        zone.assert().isNotEqualTo(Any())
        zone.hashCode().assert().isEqualTo("x".hashCode())
        zone.toString().assert().isEqualTo("x")
        listOf(zone.offsetSecondsAt(9), zone.offsetSecondsAt(10)).assert().containsExactly(0, 3600)
    }

    @Test
    fun `date-times and times parse as java time parses them`() {
        val random = Random(3)
        val utc = ZoneOffsets.fixed("UTC", 0)
        repeat(20_000) {
            val text = randomDateTime(random)
            val expected = try {
                OffsetDateTime.parse(text).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(text).atZone(ZoneOffset.UTC).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
            val actual = IsoTime.parseDateTime(text, utc)
            if (actual != null) {
                expected.assert().describedAs(text).isNotNull()
                listOf(actual.epochSecond, actual.nano.toLong()).assert().describedAs(text)
                    .containsExactly(expected!!.epochSecond, expected.nano.toLong())
            } else if (expected != null) {
                // Only forms the RFC does not need may be stricter than java.time: none are generated here.
                throw AssertionError("java.time parses `$text`, IsoTime does not")
            }
        }
        listOf(
            "2026-02-29T00:00Z", "2026-13-01T00:00Z", "2026-01-32T00:00Z", "2026-01-01 00:00Z", "26-01-01T00:00Z",
            "2026-01-01T24:00Z", "2026-01-01T00:60Z", "2026-01-01T00:00:60Z", "2026-01-01T00:00:00.Z", "2026-01-01T00:00:00.0000000000Z",
            "2026-01-01T0:00Z", "2026-01-01T00:00+8", "2026-01-01T00:00+0530", "2026-01-01T00:00+053015",
            "2026-01-01T00:00+25:00", "2026-01-01T00:00X", "2026/01/01T00:00Z", "2026-01-01T", "2026-01-01"
        )
            .forEach { IsoTime.parseDateTime(it, utc).assert().describedAs(it).isNull() }
        IsoTime.parseDateTime(
            "2024-02-29T00:00Z",
            utc
        )?.epochSecond.assert().isEqualTo(Instant.parse("2024-02-29T00:00:00Z").epochSecond)
        listOf("9:00", "09:00:0", "09:00:00.", "0900", "09-00", "09:00:00,5")
            .forEach { IsoTime.parseLocalTime(it, 0).assert().describedAs(it).isNull() }
        IsoTime.parseLocalTime("23:59:59.999999999", 0).assert().isEqualTo(LocalTime.MAX.toNanoOfDay())
    }

    @Test
    fun `moments compare and equal by instant`() {
        val moment = Moment(1, 2)
        listOf(moment == Moment(1, 2), moment == Moment(1, 3), moment.compareTo(Moment(1, 3)) < 0).assert()
            .containsExactly(true, false, true)
        moment.hashCode().assert().isEqualTo(Moment(1, 2).hashCode())
        moment.assert().isNotEqualTo(Any())
    }

    @Test
    fun `epoch days agree with java time`() {
        val random = Random(4)
        repeat(10_000) {
            val date = LocalDate.ofEpochDay(
                random.nextLong(LocalDate.of(0, 1, 1).toEpochDay(), LocalDate.of(9999, 12, 31).toEpochDay())
            )
            IsoTime.epochDay(date.year, date.monthValue, date.dayOfMonth).assert().isEqualTo(date.toEpochDay())
            DaysOfWeek.of(date.toEpochDay()).assert().isEqualTo(date.dayOfWeek.ordinal)
        }
    }

    @Test
    fun `days of the week parse as in 7x`() {
        listOf("MONDAY", "mon", " Fri ", "sunday", "SUN").map { DaysOfWeek.parse(it) }.assert()
            .containsExactly(1, 1, 1 shl 4, 1 shl 6, 1 shl 6)
        listOf("MO", "MONDAYS", "", "Montag").forEach { DaysOfWeek.parse(it).assert().describedAs(it).isNull() }
    }

    /** Random `time` conditions and instants decide as the 7.x matcher's logic does. */
    @Test
    fun `time decides as the 7x condition`() {
        val random = Random(7)
        var matched = 0
        repeat(3_000) {
            val zoneId = zones.random(random)
            val zone = ZoneId.of(zoneId)
            val config = randomConfig(random, zoneId)
            val condition = ConditionChecker(zones = JavaZones).check(config)
            val legacy = runCatching { Legacy(config, zone) }.getOrNull()
            if (legacy == null) {
                condition.assert().describedAs(describe(config)).isInstanceOf(CheckResult.Invalid::class.java)
                return@repeat
            }
            val checked = (condition as? CheckResult.Valid)?.condition
            checked.assert().describedAs("${describe(config)}: $condition").isNotNull()
            repeat(50) {
                val millis = if (legacy.notBefore != null && random.nextBoolean()) {
                    legacy.notBefore.toEpochMilli() + random.nextLong(-2, 3)
                } else {
                    random.nextLong(minMillis, maxMillis)
                }
                val expected = legacy.matches(Instant.ofEpochMilli(millis))
                if (expected) matched++
                checked!!.evaluate(
                    MapInput(time = millis)
                ).assert().describedAs("${describe(config)} at ${Instant.ofEpochMilli(millis)}")
                    .isEqualTo(expected)
            }
        }
        matched.assert().isGreaterThan(10_000)
    }

    @Test
    fun `time without an instant is false`() {
        checked(Condition.Time(daysOfWeek = DaysOfWeek.NAMES)).evaluate(MapInput()).assert().isFalse()
        checked(Condition.Time(notAfter = "2100-01-01T00:00:00Z")).evaluate(MapInput()).assert().isFalse()
    }

    @Test
    fun `bounds are compared to the millisecond the request carries`() {
        val time = checked(
            Condition.Time(notBefore = "2026-01-01T00:00:00.0005Z", notAfter = "2026-01-01T00:00:00.0025Z")
        )
        val base = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        (0L..3L).map { time.evaluate(MapInput(time = base + it)) }.assert().containsExactly(false, true, true, false)
    }

    private fun randomDateTime(random: Random): String {
        val year = if (random.nextInt(20) == 0) random.nextInt(0, 10_000) else random.nextInt(1970, 2100)
        val day = random.nextInt(1, if (random.nextInt(10) == 0) 32 else 29)
        val date = "%04d-%02d-%02d".format(year, random.nextInt(1, 13), day)
        return "${date}T${randomTime(random)}${randomOffset(random)}"
    }

    private fun randomTime(random: Random): String {
        val minutes = "%02d:%02d".format(random.nextInt(24), random.nextInt(60))
        val seconds = ":%02d".format(random.nextInt(60))
        val fraction = "." + List(random.nextInt(1, 10)) { random.nextInt(10) }.joinToString("")
        return when (random.nextInt(3)) {
            0 -> minutes
            1 -> minutes + seconds
            else -> minutes + seconds + fraction
        }
    }

    private fun randomOffset(random: Random): String {
        val sign = if (random.nextBoolean()) "+" else "-"
        val (hours, minutes, seconds) = List(3) { if (it == 0) random.nextInt(19) else random.nextInt(60) }
        return when (random.nextInt(6)) {
            0 -> ""
            1 -> "Z"
            2 -> "%s%02d:%02d".format(sign, hours, minutes)
            3 -> "%s%02d%02d".format(sign, hours, minutes)
            4 -> "%s%02d".format(sign, hours)
            else -> "%s%02d:%02d:%02d".format(sign, hours, minutes, seconds)
        }
    }

    private fun randomInstantText(random: Random): String {
        val second = random.nextLong(minMillis, maxMillis) / 1000
        val local = LocalDateTime.ofEpochSecond(second, random.nextInt(0, 3) * 500_000, ZoneOffset.UTC)
        val offset = ZoneOffset.ofHours(random.nextInt(-12, 13))
        return if (random.nextBoolean()) local.toString() else local.atOffset(offset).toString()
    }

    private fun randomDays(random: Random): List<String> = DayOfWeek.entries
        .filter { random.nextInt(3) == 0 }
        .map { if (random.nextBoolean()) it.name else it.name.take(3).lowercase() }

    private fun randomConfig(random: Random, zone: String): Condition.Time {
        fun maybe(): Boolean = random.nextInt(3) == 0
        fun localTime(): String = LocalTime.of(
            random.nextInt(24),
            random.nextInt(4) * 15,
            random.nextInt(60)
        ).toString()
        val window = random.nextBoolean()
        return Condition.Time(
            notBefore = randomInstantText(random).takeIf { maybe() },
            notAfter = randomInstantText(random).takeIf { maybe() },
            zone = zone.takeIf { random.nextInt(5) != 0 },
            daysOfWeek = randomDays(random).takeIf { random.nextBoolean() },
            from = localTime().takeIf { window || random.nextInt(20) == 0 },
            to = localTime().takeIf { window || random.nextInt(20) == 0 },
        )
    }

    private fun describe(config: Condition.Time): String = with(config) {
        "time(nb=$notBefore na=$notAfter zone=$zone days=$daysOfWeek from=$from to=$to)"
    }

    /** The checks and the logic of the 7.x `TimeConditionMatcher`, with the instant passed in. */
    private class Legacy(config: Condition.Time, zone: ZoneId) {
        private val zone: ZoneId = if (config.zone == null) ZoneId.of("UTC") else zone
        val notBefore: Instant? = config.notBefore?.let { instant(it) }
        private val notAfter: Instant? = config.notAfter?.let { instant(it) }
        private val days: Set<DayOfWeek>? = config.daysOfWeek?.map { name ->
            DayOfWeek.entries.first {
                it.name == name.uppercase() || it.name.take(3) == name.uppercase()
            }
        }?.toSet()
        private val from: LocalTime? = config.from?.let(LocalTime::parse)
        private val to: LocalTime? = config.to?.let(LocalTime::parse)

        init {
            require(notBefore != null || notAfter != null || days != null || from != null)
            require((from == null) == (to == null))
            require(from == null || from != to)
            if (notBefore != null && notAfter != null) require(notBefore.isBefore(notAfter))
            require(days == null || days.isNotEmpty())
        }

        private fun instant(value: String): Instant = try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeParseException) {
            LocalDateTime.parse(value).atZone(zone).toInstant()
        }

        fun matches(now: Instant): Boolean {
            if (notBefore != null && now.isBefore(notBefore)) return false
            if (notAfter != null && !now.isBefore(notAfter)) return false
            val local = LocalDateTime.ofInstant(now, zone)
            var day = local.dayOfWeek
            if (from != null && to != null) {
                val time = local.toLocalTime()
                val crossesMidnight = from.isAfter(to)
                val inWindow = if (crossesMidnight) !time.isBefore(from) || time.isBefore(to) else !time.isBefore(from) && time.isBefore(to)
                if (!inWindow) return false
                if (crossesMidnight && time.isBefore(to)) day = day.minus(1)
            }
            return days == null || day in days
        }
    }
}
