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
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

/** `STA-12`: what the starter derives from the heap, and the startup line that says so. */
class DerivedHeapTest {
    @Test
    fun `the startup line names each bound, what it came to and from what`() {
        val budget = HeapBudget.of(2L shl 30)
        DerivedHeap.revocationEntries(budget).assert().isEqualTo(516_222L)
        DerivedHeap.line(budget, 8_192, TraceLevel.OFF).assert()
            .contains("heap budget (heap 2048 MiB (Runtime.maxMemory())")
            .contains(
                "documents=512 MiB (absent entries 32 MiB: 65280 tenants, 256 apps; resident 480 MiB: 14566 tenants"
            )
            .contains(
                "recomputed from what is resident each revalidation pass and before each eviction (cosec.snapshot.bound)"
            )
            .contains("revocation tables=516222 entries each (290 + 230 B a pair)")
            .contains("token cache weighed at 5 B a token character")
            .contains("decision listener queues=8192 events each (1024 B an event at trace-level off)")
        DerivedHeap.line(budget, null, TraceLevel.OFF).assert().doesNotContain("decision listener queues=8192")
        DerivedHeap.line(budget, 1_024, TraceLevel.FULL).assert()
            .contains("decision listener queues=1024 events each (8192 B an event at trace-level full)")
        DerivedSnapshotSettings.line(RuntimeAuthorizationProperties(), Duration.ofSeconds(5), MockEnvironment(), 64)
            .assert().contains("heap budget (heap ").contains("decision listener queues=64 events each")
    }
}
