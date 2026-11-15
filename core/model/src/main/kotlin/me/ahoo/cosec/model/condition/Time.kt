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
 * The UTC offsets of one time zone, as a table: [offsets]`[i]` (seconds) holds from `transitions[i - 1]` (inclusive,
 * epoch seconds) to `transitions[i]`. A transition starts its new offset at its instant, as `java.time` does.
 *
 * `model/` may not read the JVM's time-zone database (`EnginePurityTest`), so `time` conditions in a region zone
 * (`Asia/Shanghai`) take this table from a [ZoneResolver] the host provides; the table must cover every instant the
 * policies are evaluated at. Fixed offsets need no host ([ZoneResolver.FIXED]).
 */
@InternalCoSecApi
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

    @InternalCoSecApi
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
@InternalCoSecApi
fun interface ZoneResolver {
    fun resolve(id: String): ZoneOffsets?

    @InternalCoSecApi
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
