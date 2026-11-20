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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `onDocumentLost` over the runner (RFC 0013 B2b step 3): it is a judgement on the key's state, an effect of the
 * transition that reads the store's `Absent`, and a read that answers for a load that no longer exists has no effect.
 * So a loss read after `close` ended the load is not reported by this source, and one read by a load abandoned at
 * the store read timeout is reported by the next load, late but not lost while the source lives.
 */
class KeyRunnerDocumentLostTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }
    private val recording = RecordingMetrics()

    /** Completed at the first `onDocumentLost`, after it is recorded: what a test that waits for the report waits on. */
    private val reported = CompletableFuture<Pair<SnapshotKey, Long>>()
    private val metrics = object : SnapshotMetrics by recording {
        override fun onDocumentLost(key: SnapshotKey, lastRevision: Long) {
            recording.onDocumentLost(key, lastRevision)
            reported.complete(key to lastRevision)
        }
    }

    /** `app-a` installed at revision 1, so seen; then gone from the store, quietly, and its next read held. */
    private fun lostAndHeld(source: SnapshotSource<Compiled, Compiled>) {
        source.load("app-a").join()
        store.dropNotifications = true
        store.remove(APP_A)
        store.hold = true
    }

    private fun lostInstalled() = recording.installs.filter { it.key == APP_A && it.origin == EntryOrigin.LOST }

    @Test
    fun `an Absent read for a load that close ended reports nothing and installs nothing`() {
        val source = testSource(store, metrics = metrics)
        lostAndHeld(source)
        val reloading = source.reload(APP_A)
        source.close()
        reloading.isDone.assert().isTrue()
        store.pending.filter { it.key == APP_A }.forEach { it.release() }
        recording.lost.assert().isEmpty()
        lostInstalled().assert().isEmpty()
    }

    @Test
    fun `an Absent read abandoned at the read timeout is reported by the next load`() {
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, storeReadTimeout = Duration.ofMillis(50))
        testSource(store, options = options, metrics = metrics).use { source ->
            lostAndHeld(source)
            // The scheduler ends the load at the read timeout; the read answers Absent only after that.
            source.reload(APP_A).get(10, TimeUnit.SECONDS)
            store.pending.filter { it.key == APP_A }.forEach { it.release() }
            recording.lost.assert().isEmpty()
            lostInstalled().assert().isEmpty()
            // The next load reads Absent again for a key it has seen: the loss is reported then.
            store.hold = false
            source.reload(APP_A).get(10, TimeUnit.SECONDS)
            // The step answers its waiters, then reports the loss: the drainer may be another thread still finishing it.
            reported.get(10, TimeUnit.SECONDS)
            recording.lost.assert().containsExactly(APP_A to 1L)
            lostInstalled().assert().hasSize(1)
        }
    }
}
