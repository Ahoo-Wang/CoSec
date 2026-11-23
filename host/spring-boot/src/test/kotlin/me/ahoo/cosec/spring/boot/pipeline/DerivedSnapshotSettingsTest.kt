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

import java.time.Duration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * `SNP-23`, `CTL-72`: what the data plane computes where it used to be told, and the one startup line that says what
 * it came to and from what.
 */
class DerivedSnapshotSettingsTest {
    @Test
    fun `the line says what was derived, from the interval, the deadline and the cores`() {
        val line = DerivedSnapshotSettings.line(
            RuntimeAuthorizationProperties(),
            Duration.ofSeconds(5),
            MockEnvironment(),
        )
        line.assert()
            .startsWith("CoSec derived, not configured: ")
            .contains("lag-warn=PT1M (6 passes")
            .contains("lag-escalate=PT5M (30 passes")
            .contains("cold-load-timeout=PT2S (40 % of the pipeline timeout)")
            .contains("cosec.authorization.snapshot.revalidate-interval PT10S")
            .contains("cosec.pipeline.timeout PT5S")
            .contains("shadow report interval=PT10S")
            .contains("cores (Runtime.availableProcessors(): the container's CPU limit)")
            .contains("this instance's configuration has no cosec.control.shadow.min-duration")
        DerivedSnapshotSettings.line(
            RuntimeAuthorizationProperties(),
            Duration.ofSeconds(5),
            MockEnvironment().withProperty("cosec.control.shadow.min-duration", "5s"),
        ).assert()
            .contains("shadow report interval=PT1S")
            .contains("cosec.control.shadow.min-duration PT5S, as this instance's configuration states it")
    }

    @Test
    fun `an instance that takes no part in shadow windows says nothing of a report interval`() {
        val properties = RuntimeAuthorizationProperties().apply { shadow.enabled = false }
        DerivedSnapshotSettings.line(properties, Duration.ofSeconds(5), MockEnvironment()).assert()
            .doesNotContain("shadow report interval")
    }

    @Test
    fun `the report interval follows the shortest life of a window where the control plane's configuration is shared`() {
        DerivedSnapshotSettings.reportInterval(MockEnvironment()).assert().isEqualTo(Duration.ofSeconds(10))
        DerivedSnapshotSettings.reportInterval(
            MockEnvironment().withProperty("cosec.control.shadow.min-duration", "5s")
        ).assert().isEqualTo(Duration.ofSeconds(1))
        DerivedSnapshotSettings.windowMinDuration(MockEnvironment()).assert().isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `the revalidation interval moves the lag thresholds with it`() {
        val properties = RuntimeAuthorizationProperties().apply { snapshot.revalidateInterval = Duration.ofSeconds(20) }
        properties.snapshot.lagWarn().assert().isEqualTo(Duration.ofMinutes(2))
        properties.snapshot.toOptions(Duration.ofSeconds(5), 1).lagEscalate.assert().isEqualTo(Duration.ofMinutes(10))
        properties.snapshot.toOptions(
            Duration.ofSeconds(5),
            1
        ).coldLoadTimeout.assert().isEqualTo(Duration.ofSeconds(2))
    }

    /**
     * `SNP-24`: the documents' share of the heap, all of it for one source; where the control plane runs, a third each to
     * its two sources and its document cache (DR1-F-5).
     */
    @Test
    fun `the snapshot sources of a process share the documents' quarter of the heap`() {
        val snapshot = RuntimeAuthorizationProperties().snapshot
        val share = HeapBudget.of().documents
        snapshot.toOptions(Duration.ofSeconds(5), 1).documentsBudget.assert().isEqualTo(share)
        snapshot.toOptions(Duration.ofSeconds(5), ControlDerived.DOCUMENT_HOLDERS).documentsBudget
            .assert().isEqualTo(share / 3)
        (share / 3).assert().isEqualTo(ControlDerived.documentCacheBytes())
    }
}
