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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-46`, `R2C-15`: the shadow pass withdraws a staged candidate no shadowing change names only when it read every open
 * change. A change it could not read may be the one shadowing, and withdrawing its candidate would cost a live window
 * its evidence.
 */
class StagedListReconciliationTest {
    private val store = InMemoryControlStore(MutableClock())
    private val reconciliation = StagedListReconciliation(store)

    @Test
    fun `a pass that could not read every change leaves an unnamed candidate staged, and complete passes withdraw it`() =
        runBlocking<Unit> {
            val key = SnapshotKey.Tenant("acme")
            store.keepStagedList()
            store.listStaged(key, WINDOW)
            val document = SnapshotDocuments.of(StoreFixtures.candidate(key).at(2, StoreFixtures.START))
            store.putCandidate(key, null, StoredCandidate(WINDOW, 1, document)).assert().isTrue()
            repeat(PASSES) {
                reconciliation.reconcile(emptyList(), complete = false)
                store.candidate(key)?.window.assert().isEqualTo(WINDOW)
                store.staged()?.windows.assert().isEqualTo(mapOf(key to setOf(WINDOW)))
            }
            reconciliation.reconcile(emptyList(), complete = true)
            store.candidate(key)?.window.assert().isEqualTo(WINDOW)
            reconciliation.reconcile(emptyList(), complete = true)
            store.candidate(key).assert().isNull()
            store.staged()?.windows.assert().isEqualTo(emptyMap<SnapshotKey, Set<String>>())
        }

    @Test
    fun `a settle that fails is tried again by the next pass`() = runBlocking<Unit> {
        var failing = true
        val flaky = object : ControlStore by store {
            override suspend fun withdrawCandidate(key: SnapshotKey, window: String): Boolean {
                check(!failing) { "Redis refused the withdrawal" }
                return store.withdrawCandidate(key, window)
            }
        }
        val passes = StagedListReconciliation(flaky)
        val key = SnapshotKey.Tenant("acme")
        store.keepStagedList()
        store.listStaged(key, WINDOW)
        repeat(2) { passes.reconcile(emptyList(), complete = true) }
        store.staged()?.windows.assert().isEqualTo(mapOf(key to setOf(WINDOW)))
        failing = false
        repeat(2) { passes.reconcile(emptyList(), complete = true) }
        store.staged()?.windows.assert().isEqualTo(emptyMap<SnapshotKey, Set<String>>())
    }

    private companion object {
        const val WINDOW = "c1@1"
        const val PASSES = 3
    }
}
