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

package me.ahoo.cosec.runtime.snapshot

import kotlin.random.Random

/**
 * One seeded script of events on one key (RFC 0013 B2b, step 1): the vocabulary of the slot model brought down to a
 * key, so that [KeyModelRun] can run [KeyTransitions] through [KeyModelRunner] as the model runs the source. [pick]
 * chooses among what is there when the event runs (a held read, a queued compilation); [millis] is how far time moves.
 */
internal data class KeyModelScript(val seed: Int, val events: List<Event>) {
    enum class Kind(val weight: Int) {
        REQUEST(10), USABLE(4), LOAD(3), PASS(3), RELOAD(5), RELOAD_FORCED(2),
        CHANGED(4), PUT_SILENT(2), REMOVE(2), CORRUPT(1), REGRESS(1), RECOMPILE(3),
        RELEASE(12), RELEASE_LATE(4), FAIL_READS(1), HEAL_READS(2), HANG(1), ADVANCE(6),
        COMPILE(10), COMPILE_FAILS(2), COMPILE_OVERFLOWS(1), REJECT_COMPILES(1), ACCEPT_COMPILES(2),
        HOUSEKEEP(6), EVICT(3), EVICT_AT_BOUND(2),

        /** A request that looked the key up just before an eviction removed it (`afterSlotLookup`). */
        STALE_LOOKUP(2),

        /** Never drawn: appended near the end of a fifth of the scripts. */
        CLOSE(0),
    }

    data class Event(val kind: Kind, val pick: Int, val millis: Long) {
        override fun toString(): String = when (kind) {
            Kind.ADVANCE -> "$kind ${millis}ms"
            Kind.RELEASE, Kind.RELEASE_LATE, Kind.HANG, Kind.COMPILE, Kind.HOUSEKEEP -> "$kind #$pick"
            else -> kind.toString()
        }
    }

    companion object {
        private val DRAWN = Kind.entries.filter { it.weight > 0 }
        private val TOTAL = DRAWN.sumOf { it.weight }

        fun generate(seed: Int, length: Int): KeyModelScript {
            val random = Random(seed)
            val events = MutableList(length) {
                var roll = random.nextInt(TOTAL)
                val kind = DRAWN.first { kind -> (roll - kind.weight).also { roll = it } < 0 }
                Event(kind, random.nextInt(Int.MAX_VALUE), random.nextLong(1, MAX_ADVANCE_MILLIS))
            }
            if (random.nextInt(CLOSE_ONE_IN) == 0) {
                events.add(random.nextInt(length * 3 / 4, length), Event(Kind.CLOSE, 0, 0))
            }
            return KeyModelScript(seed, events)
        }

        private const val MAX_ADVANCE_MILLIS = 5_000L
        private const val CLOSE_ONE_IN = 5
    }
}
