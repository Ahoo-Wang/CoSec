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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * `close` answers every request a runner accepted, a removed key's included (RFC 0013 B2b; `SNP-13`'s futures). A key
 * evicted after a pass or a lane listed it can still be offered requests; offered inside a drain whose budget is spent,
 * they wait in the source's hand-off, which `close` runs once its keys are ended, and an offerer runs after `close`.
 * The compile pool here takes tasks and never runs them, as a busy or stuck pool would not before `close` returns.
 */
class KeyRunnerCloseTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
        put(APP_B, document(1))
    }
    private val compiler = FakeCompiler()
    private val held = CopyOnWriteArrayList<Runnable>()

    /** `app-a`'s drain throws once it has released its flag: a hand-off that fails after it answered its requests. */
    @Volatile
    private var failing = false
    private val failingA = KeyDrain.Hooks(afterRelease = { if (failing) error("app-a's hand-off failed") })

    @Volatile
    private var holding = false
    private val pool = Executor { task -> if (holding) held += task else task.run() }
    private val options = SnapshotOptions(revalidateInterval = QUIET_PASS)
    private val source = SnapshotSource(
        store, SnapshotKey.Platform, compiler,
        listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsent)),
        options, ManualClock(), SnapshotMetrics.NOOP, pool, null, { 0L }, null,
        keyRunners(hooks = failingA, hooked = { it == APP_A }),
    )

    /** [key] installed, then evicted: the runner a pass listed before the eviction. */
    private fun removed(key: SnapshotKey.App): KeySlot<*> {
        source.load(key.appId).join()
        val slot = source.families.first { it.spec.kind == SnapshotKind.APP }.slots.getValue(key.appId)
        source.evict(key).assert().isTrue()
        slot.removed.assert().isTrue()
        return slot
    }

    private fun removedA(): KeySlot<*> = removed(APP_A).also { holding = true }

    @Test
    fun `close answers a request a removed key handed off before it`() {
        val slot = removedA()
        // A lane's next reload, offered from a drain whose budget is spent: handed off to the pool, which holds it.
        val reloading = DrainBudget.within(0) { slot.reloadIfHeld(force = false) }
        reloading.isDone.assert().isFalse()
        held.assert().isNotEmpty()
        source.close()
        reloading.isDone.assert().isTrue()
        reloading.join().assert().isEqualTo(Reloaded.Skipped)
    }

    @Test
    fun `a request a removed key hands off after close is run by its offerer`() {
        val slot = removedA()
        source.close()
        val reloading = DrainBudget.within(0) { slot.reloadIfHeld(force = false) }
        reloading.isDone.assert().isTrue()
        reloading.join().assert().isEqualTo(Reloaded.Skipped)
    }

    @Test
    fun `close runs every pending hand-off when one throws, and reports the failure`() {
        val a = removed(APP_A)
        val b = removed(APP_B)
        holding = true
        DrainBudget.within(0) { a.reloadIfHeld(force = false) }
        val reloadingB = DrainBudget.within(0) { b.reloadIfHeld(force = false) }
        reloadingB.isDone.assert().isFalse()
        failing = true
        assertThrows<IllegalStateException> { source.close() }.message.assert().isEqualTo("app-a's hand-off failed")
        reloadingB.isDone.assert().isTrue()
        reloadingB.join().assert().isEqualTo(Reloaded.Skipped)
    }
}
