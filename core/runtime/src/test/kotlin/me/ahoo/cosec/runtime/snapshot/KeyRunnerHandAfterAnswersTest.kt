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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Where a load's end hands its family's pass to housekeeping (RFC 0013 §5.4): after the step's answers, since that
 * pass cannot change one of them; and, when the install took the family over its cap, the pass runs in place before
 * them (`SNP-14`'s back-pressure), as before.
 */
class KeyRunnerHandAfterAnswersTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        listOf(APP_A, APP_B, APP_C).forEach { put(it, document(1)) }
    }

    /** What happened, in order: "answered" from a request's completion, "hand" from housekeeping's `execute`. */
    private val order = CopyOnWriteArrayList<String>()

    /** Records each task handed to housekeeping, and keeps it: no pass runs unless the test runs it. */
    private val housekeeping = object : AbstractExecutorService() {
        val tasks = CopyOnWriteArrayList<Runnable>()

        override fun execute(command: Runnable) {
            order += "hand"
            tasks += command
        }

        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = true
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private fun source(): SnapshotSource<Compiled, Compiled> = SnapshotSource(
        store,
        SnapshotKey.Platform,
        FakeCompiler(),
        listOf(SnapshotFamily(SnapshotKind.APP, FakeCompiler(), OPTIONS.maxApps, OPTIONS.maxAbsentApps)),
        OPTIONS,
        ManualClock(),
        SnapshotMetrics.NOOP,
        directExecutor,
        null,
        { 0L },
        housekeeping,
        keyRunners(),
    ).also { it.load().join() }

    /** [appId] requested while its read is held, then answered: [onAnswer] runs as the request completes. */
    private fun SnapshotSource<Compiled, Compiled>.loadHeld(appId: String, onAnswer: () -> Unit = {}) {
        store.hold = true
        val answer = load(appId)
        answer.isDone.assert().isFalse()
        answer.whenComplete { _, _ ->
            order += "answered"
            onAnswer()
        }
        store.hold = false
        store.pending.single { it.key.toString() == "app:$appId" }.release()
        answer.isDone.assert().isTrue()
    }

    /**
     * The request is answered before any pass is handed to housekeeping, and a hand follows. A load that confirms the
     * local copy follows the install, and its end asks for the bound again: a second hand.
     */
    private fun answeredThenHanded() {
        order.first().assert().isEqualTo("answered")
        order.drop(1).assert().isNotEmpty().allMatch { it == "hand" }
    }

    @Test
    fun `under the cap, a load's end answers its request, then hands the pass to housekeeping, in the same step`() {
        source().use { source ->
            source.load("app-a").join()
            order.clear()
            // Two documents against a bound of one: beyond the bound, within the cap of two.
            source.loadHeld("app-b")
            answeredThenHanded()
        }
    }

    @Test
    fun `an answer whose completion callback throws still leaves the pass requested`() {
        source().use { source ->
            source.load("app-a").join()
            order.clear()
            source.loadHeld("app-b") { throw IllegalStateException("a caller's callback") }
            answeredThenHanded()
        }
    }

    @Test
    fun `over the cap, the pass runs in place before the request is answered`() {
        source().use { source ->
            source.load("app-a").join()
            source.load("app-b").join()
            // Housekeeping holds the passes it was handed: the family holds two documents, its cap.
            var heldAtAnswer = -1
            source.loadHeld("app-c") { heldAtAnswer = source.entries().count { it.key is SnapshotKey.App } }
            heldAtAnswer.assert().isEqualTo(OPTIONS.maxApps)
        }
    }

    private companion object {
        val OPTIONS = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = 1)
    }
}
