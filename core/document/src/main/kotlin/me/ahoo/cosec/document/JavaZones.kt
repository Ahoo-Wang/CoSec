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

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.ZoneOffsets
import me.ahoo.cosec.model.condition.ZoneResolver
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Region time zones (`Asia/Shanghai`) for `time` conditions, from `java.time`'s zone database: the host's resolver that
 * the pure condition model leaves out (RFC 0005 §3.2). Each zone's transitions are expanded once, up to [HORIZON_YEAR],
 * and kept: there are a few hundred zones at most.
 */
@InternalCoSecApi
object JavaZones : ZoneResolver {
    /** Daylight-saving rules are expanded into transitions until the end of this year. */
    const val HORIZON_YEAR: Int = 2100

    /** Fixed offsets first, then regions: what documents compile with. */
    val RESOLVER: ZoneResolver = ZoneResolver.withRegions(this)

    private val zones = ConcurrentHashMap<String, Optional<ZoneOffsets>>()

    /**
     * A zone that cannot be loaded resolves to nothing, like an unknown one, and is not tried again. Only ids the JVM's
     * zone database names are kept, so the cache holds at most its few hundred zones: a document's author writes the
     * id, and an unknown one kept would grow it with every new text (round 2 review).
     */
    override fun resolve(id: String): ZoneOffsets? {
        if (id !in ZoneId.getAvailableZoneIds()) {
            return null
        }
        return zones.computeIfAbsent(id) {
            Optional.ofNullable(
                try {
                    load(it)
                } catch (_: DateTimeException) {
                    null
                },
            )
        }.orElse(null)
    }

    /** How many zones are kept: for the test that unknown ids are not. */
    internal fun cachedZones(): Int = zones.size

    private fun load(id: String): ZoneOffsets {
        val rules = ZoneId.of(id).rules
        val transitions = ArrayList<ZoneOffsetTransition>(rules.transitions)
        val lastYear = transitions.lastOrNull()?.dateTimeBefore?.year ?: Instant.EPOCH.atZone(ZoneId.of("UTC")).year
        for (year in lastYear..HORIZON_YEAR) {
            rules.transitionRules.forEach { transitions += it.createTransition(year) }
        }
        val sorted = transitions.distinctBy { it.toEpochSecond() }.sortedBy { it.toEpochSecond() }
            .filter { it.dateTimeBefore.year <= HORIZON_YEAR }
        val first = sorted.firstOrNull()?.offsetBefore ?: rules.getOffset(Instant.EPOCH)
        return ZoneOffsets(
            id,
            LongArray(sorted.size) { sorted[it].toEpochSecond() },
            IntArray(sorted.size + 1) { if (it == 0) first.totalSeconds else sorted[it - 1].offsetAfter.totalSeconds },
        )
    }
}
