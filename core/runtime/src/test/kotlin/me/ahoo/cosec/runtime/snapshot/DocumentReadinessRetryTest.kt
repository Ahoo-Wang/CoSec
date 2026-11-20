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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration

/**
 * `SNP-21` (R2D-6): every document readiness waits for is read again after a failed first read, without a request. An
 * instance that started while the store could not be read becomes ready once it can: no request reaches a host that
 * is not ready, so nothing else would read the platform document again.
 */
class DocumentReadinessRetryTest {
    private val store = ControllableStore()
    private val shop = SnapshotKey.App("shop")
    private val routed = SnapshotKey.App("routed")
    private val options = SnapshotOptions(
        revalidateInterval = QUIET_PASS,
        prewarmInitialBackoff = Duration.ofMillis(20),
        prewarmMaxBackoff = Duration.ofMillis(40),
    )

    @Test
    fun `a host whose first reads failed becomes ready once the store answers, with no request`() {
        store.put(SnapshotKey.Platform, document(1))
        store.put(shop, document(1))
        store.put(routed, document(1))
        store.failure = IOException("the store is restarting")
        testSource(store, options = options).use { source ->
            val readiness = DocumentReadiness(source, listOf(shop))
            readiness.start()
            readiness.prewarmAlso(listOf(routed))
            await { store.reads.get() >= READS_WHILE_DOWN }
            readiness.missing().assert().containsExactlyInAnyOrder(SnapshotKey.Platform, shop, routed)

            store.failure = null
            await { readiness.missing().isEmpty() }
            source.peek()!!.policiesCompiled.revision.assert().isEqualTo(1)
        }
    }

    /** The negative: while the store cannot be read, the host stays not ready, however long it waits. */
    @Test
    fun `a host whose store cannot be read stays not ready`() {
        store.put(SnapshotKey.Platform, document(1))
        store.failure = IOException("the store is down")
        testSource(store, options = options).use { source ->
            val readiness = DocumentReadiness(source, emptyList())
            readiness.start()
            await { store.reads.get() >= READS_WHILE_DOWN }
            readiness.missing().assert().containsExactly(SnapshotKey.Platform)
        }
    }

    /**
     * Another prewarm completing first (a gateway's route apps) does not count as the configured documents': here the
     * store is down, and only the routed app answers, from a local copy.
     */
    @Test
    fun `the configured documents wait for their own prewarm, not another`() {
        store.put(SnapshotKey.Platform, document(1))
        store.localCopies[routed] = document(1)
        store.failure = IOException("the store is down")
        testSource(store, options = options).use { source ->
            val readiness = DocumentReadiness(source, listOf(shop))
            readiness.start()
            source.prewarm(listOf(routed)).join()
            source.isPrewarmed.assert().isTrue()
            await { store.reads.get() >= READS_WHILE_DOWN }
            readiness.missing().assert().containsExactlyInAnyOrder(SnapshotKey.Platform, shop)
        }
    }

    private companion object {
        /** Enough reads for the prewarm to have retried after the first failure. */
        const val READS_WHILE_DOWN = 6
    }
}
