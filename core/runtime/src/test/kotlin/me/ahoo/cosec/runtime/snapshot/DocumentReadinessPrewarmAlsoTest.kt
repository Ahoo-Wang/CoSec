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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `SNP-20`: documents a host names once its other beans exist (a gateway's routes) are prewarmed the way the configured
 * ones are, and readiness waits for each until its prewarm has completed once: a document or the store's "no such
 * document", never a failed read.
 */
class DocumentReadinessPrewarmAlsoTest {
    private val store = ControllableStore()
    private val shop = SnapshotKey.App("shop")
    private val madeUp = SnapshotKey.App("made-up")

    @Test
    fun `documents added later are waited for until their prewarm completes, one without a document as well`() {
        store.put(SnapshotKey.Platform, document(1))
        store.put(shop, document(1))
        testSource(store).use { source ->
            val readiness = DocumentReadiness(source, emptyList())
            readiness.start()
            runBlocking { readiness.await() }.assert().isEmpty()
            store.hold = true
            readiness.prewarmAlso(listOf(shop, madeUp, SnapshotKey.Platform))
            readiness.missing().assert().containsExactlyInAnyOrder(shop, madeUp)
            store.pending.toList().forEach { it.release() }
            runBlocking { readiness.await() }.assert().isEmpty()
            source.holds(shop).assert().isTrue()
            source.holds(madeUp).assert().isTrue()
        }
    }

    @Test
    fun `a key prewarmed already is not prewarmed again`() {
        store.put(SnapshotKey.Platform, document(1))
        testSource(store).use { source ->
            val readiness = DocumentReadiness(source, listOf(shop))
            readiness.start()
            runBlocking { readiness.await() }
            store.hold = true
            readiness.prewarmAlso(listOf(shop))
            readiness.prewarmAlso(listOf(madeUp))
            readiness.prewarmAlso(listOf(madeUp))
            store.pending.map { it.key }.filter { it == madeUp }.size.assert().isEqualTo(1)
            readiness.missing().assert().containsExactly(madeUp)
            store.pending.toList().forEach { it.release() }
            runBlocking { readiness.await() }.assert().isEmpty()
        }
    }
}
