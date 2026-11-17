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

package me.ahoo.cosec.runtime.snapshot

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Ask
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Claim
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/**
 * Every order of events on one key, up to [DEPTH] events, through [KeyTransitions] (RFC 0013 B2b, step 1). Each step is
 * checked by [KeyStepChecks]; at every point reached, a copy of the world is settled (every read and compilation
 * answered, every retry run) and every waiter must have been answered, once, by the load whose handle it hangs on.
 *
 * Orders that lead to the same point are explored once: a point is the key's state and the world's outstanding work
 * with the load and handle numbers left out, which is all that decides what can follow ([canonical]).
 */
class KeyTransitionsOrderTest {
    /** Only its identity is used, and nothing prints it: [KeyHarness] names a pass in its log. */
    private val pass = mockk<Pass>()
    private val seen = HashMap<String, Int>()
    private val reached = sortedSetOf<String>()
    private var steps = 0

    @Test
    fun everyOrderOfEventsKeepsEveryWaiterToItsOwnLoad() {
        explore(KeyHarness.of(), DEPTH)
        explore(KeyHarness.of(shared = true), DEPTH)
        println("Key transitions: ${seen.size} points, $steps steps; reached $reached")
        REACHED.forEach { reached.assert().describedAs("orders reaching '$it'").contains(it) }
    }

    private fun explore(harness: KeyHarness, depth: Int) {
        val point = canonical(harness)
        if ((seen[point] ?: -1) >= depth) return
        seen[point] = depth
        val settled = harness.copy()
        settled.settle()
        settled.checkAllAnswered()
        failIfViolated(settled)
        if (depth == 0) return
        for ((name, event) in events(harness)) {
            val next = harness.copy()
            event(next)
            steps++
            note(name, harness, next)
            failIfViolated(next)
            explore(next, depth - 1)
        }
    }

    private fun failIfViolated(harness: KeyHarness) {
        if (harness.violations.isEmpty()) return
        throw AssertionError(
            buildString {
                appendLine(harness.violations.first())
                appendLine("Events:")
                harness.log.forEach { appendLine("  $it") }
            }
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun events(world: KeyHarness): List<Pair<String, (KeyHarness) -> Unit>> = buildList {
        add("request" to { key: KeyHarness -> key.request(Ask.Request) })
        add("usable" to { key: KeyHarness -> key.request(Ask.Usable) })
        add("load" to { key: KeyHarness -> key.request(Ask.Load(force = false)) })
        add("pass" to { key: KeyHarness -> key.request(Ask.Load(force = false, pass = pass)) })
        add("reload" to { key: KeyHarness -> key.request(Ask.Reload(force = false)) })
        add("reload forced" to { key: KeyHarness -> key.request(Ask.Reload(force = true)) })
        add("reload if held" to { key: KeyHarness -> key.request(Ask.ReloadIfHeld(force = false)) })
        add("changed" to { key: KeyHarness -> key.offer(KeyEvent.Changed) })
        if (world.epoch < MAX_EPOCH) add("recompile" to { key: KeyHarness -> key.recompile() })
        if (world.retries > 0) add("retry" to { key: KeyHarness -> key.offer(KeyEvent.RetryDue) })
        if (world.nanos == 0L) {
            add(
                "time passes" to { key: KeyHarness -> key.nanos = KeyHarness.STORE_READ_TIMEOUT.toNanos() + 1 }
            )
        }
        if (!world.closed) add("close" to { key: KeyHarness -> key.offer(KeyEvent.Close) })
        val load = world.state.load
        if (load != null && load.id in world.reads) {
            val id = load.id
            add("read r1" to { key: KeyHarness -> key.offer(KeyEvent.ReadDone(id, KeyHarness.present(1))) })
            add("read r2" to { key: KeyHarness -> key.offer(KeyEvent.ReadDone(id, KeyHarness.present(2))) })
            add("read absent" to { key: KeyHarness -> key.offer(KeyEvent.ReadDone(id, SnapshotRead.Absent)) })
            add("read unchanged" to { key: KeyHarness -> key.offer(KeyEvent.ReadDone(id, SnapshotRead.Unchanged)) })
            add(
                "read fails" to { key: KeyHarness -> key.offer(KeyEvent.ReadFailed(id, IllegalStateException("down"))) }
            )
            add(
                "read undecodable" to { key: KeyHarness -> key.offer(KeyEvent.ReadFailed(id, SnapshotFormatException("bad"))) }
            )
            add("read times out" to { key: KeyHarness -> key.offer(KeyEvent.ReadTimedOut(id)) })
        }
        world.reads.firstOrNull { it != load?.id }?.let { old ->
            add("old read answers" to { key: KeyHarness -> key.offer(KeyEvent.ReadDone(old, KeyHarness.present(1))) })
        }
        if (load != null && load.id in world.compiles) {
            val id = load.id
            add("compiled" to { key: KeyHarness -> key.offer(KeyEvent.Compiled(id, DocumentState.Present("c$id"), 1)) })
            add(
                "compile fails" to { key: KeyHarness ->
                    key.offer(KeyEvent.Compiled(id, DocumentState.Failed(IllegalStateException("no")), 1))
                }
            )
            add(
                "compile crashes" to { key: KeyHarness ->
                    key.offer(KeyEvent.CompileCrashed(id, SnapshotUnavailableException(KeyHarness.KEY, "overflow")))
                }
            )
            add(
                "compile rejected" to { key: KeyHarness -> key.offer(KeyEvent.CompileRejected(id, RejectedExecutionException())) }
            )
        }
        if (load != null) {
            add(
                "effect fails" to { key: KeyHarness -> key.offer(KeyEvent.EffectFailed(load.id, IllegalStateException("x"))) }
            )
        }
        add("drop if empty" to { key: KeyHarness -> key.offer(evict(onlyIfEmpty = true, Claim.UNCLAIMED)) })
        add("evict unclaimed" to { key: KeyHarness -> key.offer(evict(onlyIfEmpty = false, Claim.UNCLAIMED)) })
        if (world.transitions.evictable(world.state, absent = null, onlyIfEmpty = false)) {
            add("evict claimed" to { key: KeyHarness -> key.offer(evict(onlyIfEmpty = false, Claim.CLAIMED)) })
            add("evict at bound" to { key: KeyHarness -> key.offer(evict(onlyIfEmpty = false, Claim.AT_BOUND)) })
        } else {
            add(
                "evict not evictable" to { key: KeyHarness -> key.offer(evict(onlyIfEmpty = false, Claim.NOT_EVICTABLE)) }
            )
        }
    }

    private fun evict(onlyIfEmpty: Boolean, claim: Claim) = KeyEvent.Evict(onlyIfEmpty, claim, CompletableFuture())

    /** Which kinds of situation the search got to, so that it cannot pass by never reaching them. */
    private fun note(name: String, before: KeyHarness, after: KeyHarness) {
        val loading = before.state.load != null
        SITUATIONS.filter { (_, holds) ->
            holds(
                Moved(name, before, after, loading)
            )
        }.forEach { (situation, _) -> reached += situation }
    }

    /** One event of the search, for [SITUATIONS]: its name, the world before and after it, and whether a load was in flight. */
    private class Moved(val name: String, val before: KeyHarness, val after: KeyHarness, val loading: Boolean)

    /** The point [harness] is at, with numbers that only name loads and handles left out. */
    private fun canonical(harness: KeyHarness): String = buildString {
        val state = harness.state
        state.installed?.let {
            append(
                "I"
            ).append(it.origin).append(it.revision).append('e').append(state.installedEpoch)
        }
        state.load?.let { load ->
            append(
                "|L"
            ).append(load.phase.let { if (it is LoadPhase.Compiling) "C${it.read.document.revision}" else "R" })
            append(load.force).append(load.recompiles).append(load.fromLocalCopy).append(load.askedDigest != null)
            append('e').append(load.epoch).append(load.pass != null)
            append(harness.nanos - load.startedNanos > KeyHarness.STORE_READ_TIMEOUT.toNanos())
            append('a').append(load.alsoAnswers.size).append(waitedOn(harness, load.handle))
            append(load.id in harness.reads).append(load.id in harness.compiles)
        }
        append("|g").append(state.again).append(state.againForced).append(state.next?.let { waitedOn(harness, it) })
        append(state.removed).append(state.retryDelay.toMillis())
        append(
            "|E"
        ).append(harness.epoch).append(harness.closed).append(harness.seen?.revision).append(harness.nanos > 0)
        append(harness.retries.coerceAtMost(2)).append(harness.reads.any { it != state.load?.id })
        append(harness.config.shared)
    }

    private fun waitedOn(harness: KeyHarness, handle: KeyHandle<String>): String =
        "w${harness.hung.values.count { it === handle }.coerceAtMost(1)}${handle in harness.startedAfter}"

    private companion object {
        const val DEPTH = 8
        const val MAX_EPOCH = 2

        /** What the search must get to, each with how a step shows it got there. */
        private val SITUATIONS: List<Pair<String, (Moved) -> Boolean>> = listOf(
            "withheld compilation answered by the next load" to {
                it.loading && it.after.state.load?.alsoAnswers?.isNotEmpty() == true
            },
            "reload during a load" to { it.loading && it.name.startsWith("reload") },
            "pass skips a stuck load" to { it.loading && it.name == "pass" && it.after.direct.size > it.before.direct.size },
            "eviction at bound" to { it.name == "evict at bound" },
            "eviction claimed" to { it.name == "evict claimed" },
            "request redispatched" to { it.after.state.removed && it.after.direct.values.any { e -> e is KeyEffect.Redispatch } },
            "lost document" to { it.after.installs.any { e -> e.origin == EntryOrigin.LOST } },
            "recompile pending" to { it.after.installs.any { e -> e.origin == EntryOrigin.RECOMPILE_PENDING } },
            "close mid-load" to { it.loading && it.name == "close" },
            "next handle waiting" to { it.after.state.again && it.after.state.next != null },
            "old read dropped" to { it.name == "old read answers" },
            "answered by a compilation" to { it.name.startsWith("compile") && it.after.answered.size > it.before.answered.size },
        )
        private val REACHED = SITUATIONS.map { it.first }
    }
}
