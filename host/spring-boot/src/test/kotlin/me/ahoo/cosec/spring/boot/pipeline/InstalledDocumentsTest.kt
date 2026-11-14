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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * #1238: an instance's heartbeat reports a document as installed only while it is usable. One that was read but does not
 * compile stays out of the propagation query's numerator (its instance is still in the denominator), so an operator
 * never reads 1/1 for a revision the instance cannot evaluate.
 */
class InstalledDocumentsTest {
    private val acme = SnapshotKey.Tenant("acme")
    private val now = Instant.parse("2026-11-08T00:00:00Z")
    private val store = InMemorySnapshotDocumentStore()

    @Volatile
    private var tenantCompiles = true

    private val source = DocumentSnapshots.source(store, { key, document ->
        check(tenantCompiles || key != acme) { "cannot compile $key" }
        document.revision
    })

    private fun publish(key: SnapshotKey, revision: Long) {
        store.put(key, SnapshotDocument(2, revision, now, byteArrayOf()))
    }

    private fun installed() = CoSecSnapshotAutoConfiguration.Shadow.installedDocuments(source.entries(), now)
        .mapValues { it.value.revision }

    @Test
    fun `a document that compiles is installed, one that failed or is absent is not`() {
        publish(SnapshotKey.Platform, 3)
        publish(acme, 7)
        source.use {
            it.loadAll(listOf(acme, SnapshotKey.Tenant("absent"))).join()
            installed().assert().isEqualTo(mapOf(SnapshotKey.Platform to 3L, acme to 7L))
        }
    }

    @Test
    fun `a revision that failed to compile is not installed, and is again once it compiles`() {
        publish(SnapshotKey.Platform, 3)
        publish(acme, 7)
        tenantCompiles = false
        source.use {
            val entry = it.loadAll(listOf(acme)).join().scoped.single()
            entry.state.assert().isInstanceOf(DocumentState.Failed::class.java)
            entry.revision.assert().isEqualTo(7L)
            installed().assert().isEqualTo(mapOf(SnapshotKey.Platform to 3L))

            tenantCompiles = true
            publish(acme, 8)
            it.revalidate().join()
            installed().assert().isEqualTo(mapOf(SnapshotKey.Platform to 3L, acme to 8L))
        }
    }
}
