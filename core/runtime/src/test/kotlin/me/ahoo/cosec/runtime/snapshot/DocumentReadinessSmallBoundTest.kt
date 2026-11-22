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
import me.ahoo.cosec.runtime.snapshot.SnapshotSourceTest.Companion.await
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `SNP-24`: a family's bound smaller than the documents a host prewarms (a small pod, whose derived bound holds fewer
 * documents than it names) still lets the host turn ready. Readiness waits for each prewarmed document until its prewarm
 * has answered once, and an eviction afterwards does not take that back: the documents evicted are read again on their
 * next request, as any other.
 */
class DocumentReadinessSmallBoundTest {
    @Test
    fun `a host whose bound is smaller than its prewarm set turns ready, and holds no more than its cap`() {
        val store = InMemorySnapshotDocumentStore()
        store.put(SnapshotKey.Platform, document(1))
        val apps = (0 until PREWARMED).map { SnapshotKey.App("app-$it") }
        apps.forEach { store.put(it, document(1)) }
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = BOUND)
        testSource(store, options = options).use { source ->
            val readiness = DocumentReadiness(source, apps)
            readiness.start()
            await { readiness.missing().isEmpty() }
            // The prewarm did outgrow the bound: documents it installed were evicted again, and the host is ready anyway.
            val (held, _) = source.heldCounts(SnapshotKind.APP)
            held.assert().isLessThan(PREWARMED).isLessThanOrEqualTo(2 * BOUND)
            readiness.missing().assert().isEmpty()
        }
    }

    private companion object {
        const val BOUND = 4
        const val PREWARMED = 32
    }
}
