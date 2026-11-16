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

import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.random.Random

/**
 * One scripted run of the slot model, as data: what the store holds and tells the listener while it subscribes, then
 * the events in order. Being data, a failing run can be shrunk by leaving events out ([SnapshotSlotModelPropertyTest]).
 * An event that picks among what is waiting (a read, a compilation, a housekeeping task) picks by [Event.pick] modulo
 * how many wait then, so it stays valid when the events before it change.
 */
internal data class SlotModelScript(
    val initial: List<SnapshotKey>,
    val duringSubscribe: Subscribe,
    val events: List<Event>,
    /** What goes wrong while the lag phase checks that lost invalidations are made good by the passes. */
    val lag: Lag = Lag.NONE,
    /** The document [lag] happens to. */
    val lagTarget: SnapshotKey = SHARED,
    /** Documents a pass reloads at once: with one, a pass that waits on a load waits for good (`SNP-6`). */
    val lanes: Int = 2,
) {
    /** In the lag phase: nothing, the target's first read hangs, or its compilation never runs. */
    enum class Lag { NONE, HANG_READ, STUCK_COMPILE }

    /** What the store tells the source's listener before `subscribe` returns. */
    enum class Subscribe { NOTHING, RESET, SHARED_CHANGED, APP_CHANGED, TENANT_CHANGED }

    /** What an event acts on. */
    enum class On { STORE, SOURCE, THREADS }

    enum class Kind(val on: On) {
        PUT(On.STORE), REMOVE(On.STORE), CORRUPT(On.STORE), REGRESS(On.STORE), FAIL_READS(On.STORE),
        HEAL_READS(On.STORE), DROP_NEXT_NOTIFICATION(On.STORE), RESET(On.STORE),
        RELEASE(On.STORE), RELEASE_LATE(On.STORE), HANG(On.STORE),
        REQUEST(On.SOURCE), REQUEST_SHARED(On.SOURCE), REVALIDATE(On.SOURCE), RECOMPILE(On.SOURCE), RELOAD(On.SOURCE),
        SETTLED(On.SOURCE), PREWARM(On.SOURCE), TICK(On.SOURCE), PEEK(On.SOURCE), CLOSE(On.SOURCE),
        COMPILE(On.THREADS), COMPILE_FAILS(On.THREADS), COMPILE_OVERFLOWS(On.THREADS), COMPILE_RECOMPILES(On.THREADS),
        REJECT_COMPILES(On.THREADS), ACCEPT_COMPILES(On.THREADS), HOUSEKEEP(On.THREADS), ADVANCE(On.THREADS),
    }

    data class Event(val kind: Kind, val key: SnapshotKey? = null, val pick: Int = 0, val millis: Long = 0) {
        override fun toString(): String = buildString {
            append(kind.name.lowercase())
            key?.let { append(' ').append(it) }
            if (kind in PICKING) append(" #").append(pick)
            if (kind == Kind.ADVANCE) append(' ').append(millis).append("ms")
        }
    }

    companion object {
        val SHARED: SnapshotKey = SnapshotKey.Platform
        val FAMILY: List<SnapshotKey> = listOf(APP_A, APP_B, TENANT_A, TENANT_B)
        private val KEYS = listOf(SHARED) + FAMILY
        private val PICKING = setOf(Kind.RELEASE, Kind.RELEASE_LATE, Kind.HANG, Kind.COMPILE, Kind.HOUSEKEEP)
        private val STEPS = listOf(50L, 300L, 2_500L, 11_000L, 31_000L)

        /** The weight of each kind: loads, answers and compilations most, faults seldom. */
        private val WEIGHTS: List<Pair<Kind, Int>> = listOf(
            Kind.PUT to 10, Kind.REMOVE to 2, Kind.CORRUPT to 1, Kind.REGRESS to 3, Kind.FAIL_READS to 2,
            Kind.HEAL_READS to 3, Kind.DROP_NEXT_NOTIFICATION to 2, Kind.RESET to 1,
            Kind.RELEASE to 20, Kind.RELEASE_LATE to 8, Kind.HANG to 1,
            Kind.REQUEST to 12, Kind.REQUEST_SHARED to 2, Kind.REVALIDATE to 4, Kind.RECOMPILE to 3, Kind.RELOAD to 2,
            Kind.SETTLED to 1, Kind.PREWARM to 1, Kind.TICK to 2, Kind.PEEK to 2,
            Kind.COMPILE to 20, Kind.COMPILE_FAILS to 2, Kind.COMPILE_OVERFLOWS to 1, Kind.COMPILE_RECOMPILES to 2,
            Kind.REJECT_COMPILES to 1, Kind.ACCEPT_COMPILES to 2, Kind.HOUSEKEEP to 6,
            Kind.ADVANCE to 8,
        )
        private val TOTAL = WEIGHTS.sumOf { it.second }

        fun generate(seed: Int, length: Int): SlotModelScript {
            val random = Random(seed)
            val initial = KEYS.filter { random.nextInt(3) != 0 }
            val subscribe = if (random.nextInt(4) == 0) Subscribe.entries.random(random) else Subscribe.NOTHING
            val events = MutableList(length) { event(random) }
            // A third of the runs close the source somewhere; the others run to the end and converge.
            if (random.nextInt(3) == 0) events[random.nextInt(length)] = Event(Kind.CLOSE)
            // Drawn after the events, so that the events of a seed stay what they were before these existed.
            val lag = Lag.entries.random(random)
            val target = KEYS.random(random)
            val lanes = 1 + random.nextInt(2)
            return SlotModelScript(initial, subscribe, events, lag, target, lanes)
        }

        private fun event(random: Random): Event {
            var draw = random.nextInt(TOTAL)
            val kind = WEIGHTS.first { (_, weight) -> (draw < weight).also { draw -= weight } }.first
            return when (kind) {
                Kind.PUT, Kind.REMOVE, Kind.CORRUPT, Kind.REGRESS, Kind.RELOAD -> Event(kind, KEYS.random(random))
                Kind.REQUEST, Kind.SETTLED, Kind.PREWARM, Kind.PEEK -> Event(kind, FAMILY.random(random))
                Kind.ADVANCE -> Event(kind, millis = STEPS.random(random))
                else -> Event(kind, pick = random.nextInt(1 shl 16))
            }
        }
    }
}
