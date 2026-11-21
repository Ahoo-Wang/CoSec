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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.shadow.StagedDocuments
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `OBS-8`: `cosec.shadow.staged.age` is how long ago this instance last read the staged list: NaN before the first read,
 * back to zero on each read, rising while the list cannot be read, so an instance that samples by a list it no longer
 * reads shows.
 */
class ShadowStagedAgeMetricTest {
    private class MovableClock(var now: Instant = Instant.parse("2026-11-20T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private class Store : CandidateStore {
        @Volatile
        var failing = false

        override suspend fun candidate(key: SnapshotKey): StoredCandidate? = null

        override suspend fun staged(): StagedCandidates {
            check(!failing) { "the store is down" }
            return StagedCandidates.NONE
        }
    }

    @Test
    fun `the age is NaN before a read, zero after one, and rises while the list cannot be read`() {
        val clock = MovableClock()
        val store = Store()
        val staged = StagedDocuments(store, clock)
        val registry = SimpleMeterRegistry()
        CoSecMeters.bind(registry, staged)
        val age = registry.get(CoSecMeters.SHADOW_STAGED_AGE).gauge()
        age.value().isNaN().assert().isTrue()

        runBlocking { staged.refresh() }
        age.value().assert().isEqualTo(0.0)
        clock.now = clock.now.plusSeconds(10)
        age.value().assert().isEqualTo(10.0)

        store.failing = true
        runBlocking { staged.refresh() }
        clock.now = clock.now.plusSeconds(20)
        age.value().assert().isEqualTo(30.0)

        store.failing = false
        runBlocking { staged.refresh() }
        age.value().assert().isEqualTo(0.0)
    }
}
