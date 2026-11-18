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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The oracle itself (`SNP-7` at the claim, [SlotModel.claimed]): each of its eviction checks fails when it should, apart
 * from any run of the source. The property test's scripts rarely make a claim on a stale choice, so this is where the
 * checks are shown able to fail.
 */
class SlotModelOracleTest {
    private val store = SlotModelStore({ 0L }, { 0L }).apply { hold = false }
    private val model = SlotModel(store, SlotModelThreads(), OPTIONS, listOf(APP_A, APP_B))

    /** An absent entry of [key], answered by a live read and reported installed through the model's metrics. */
    private fun installedAbsent(key: SnapshotKey): SnapshotEntry<*> {
        store.read(key).join()
        return SnapshotEntry(key, DocumentState.Absent, 0, null, Instant.EPOCH, EntryOrigin.ABSENT)
            .also(model.metrics::onInstall)
    }

    @Test
    fun `an eviction claimed while its kind is over its bound passes`() {
        installedAbsent(APP_A)
        installedAbsent(APP_B)
        model.claimed(APP_A)
        model.violations.assert().isEmpty()
        model.counts.evictionsAtClaim.assert().isEqualTo(1)
    }

    @Test
    fun `an eviction claimed while its kind is within its bound is a violation`() {
        installedAbsent(APP_A)
        model.claimed(APP_A)
        model.violations.assert().containsExactly("evicted app:app-a while its family held 1 absent entries, within 1")
    }

    @Test
    fun `a claim of a key with no install reported is a violation`() {
        model.claimed(APP_A)
        model.violations.assert().containsExactly("claimed the eviction of app:app-a, which holds no install reported")
    }

    @Test
    fun `under the runner, a key whose install leaves the source with no claim seen is a violation`() {
        model.judgesClaims = true
        val entry = installedAbsent(APP_A)
        val b = installedAbsent(APP_B)
        model.afterEvent(listOf(b), closed = false)
        model.violations.assert().containsExactly("removed $entry of app:app-a without a claim")
    }

    @Test
    fun `under Slot, the same removal is judged after its event`() {
        installedAbsent(APP_A)
        model.afterEvent(emptyList(), closed = false)
        model.violations.assert().containsExactly("evicted app:app-a while its family held 1 absent entries, within 1")
        model.counts.evictionsAfterEvent.assert().isEqualTo(1)
    }

    @Test
    fun `an entry whose eviction was claimed and is still served after its event is a violation`() {
        model.judgesClaims = true
        val a = installedAbsent(APP_A)
        val b = installedAbsent(APP_B)
        model.claimed(APP_A)
        model.afterEvent(listOf(a, b), closed = false)
        model.violations.assert().contains("claimed the eviction of app:app-a and still serves $a")
    }

    private companion object {
        val APP_A = SnapshotKey.App("app-a")
        val APP_B = SnapshotKey.App("app-b")
        val OPTIONS = SnapshotOptions(maxApps = 1, maxTenants = 1, maxAbsent = 1)
    }
}
