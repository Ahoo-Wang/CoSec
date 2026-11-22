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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the starter derives from `cosec.pipeline.timeout` and the shutdown phase (`PIP-33`, `OBS-10`), and says it did. */
class DerivedTimeoutsTest {
    @Test
    fun `without either deadline stated, every value is the default it replaced`() {
        val environment = MockEnvironment()
        DerivedTimeouts.budgets(environment).revocationRead.assert().isEqualTo(300.milliseconds)
        DerivedTimeouts.drain(environment).assert().isEqualTo(Duration.ofSeconds(5))
        DerivedTimeouts.controlRequiredListener(environment).assert().isEqualTo(Duration.ofSeconds(5))
        DerivedTimeouts.snapshotLocalMaxAge(Duration.ofSeconds(10)).assert().isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `the values follow the deadlines an operator states`() {
        val environment = MockEnvironment()
            .withProperty("cosec.pipeline.timeout", "2s")
            .withProperty("spring.lifecycle.timeout-per-shutdown-phase", "1m")
        val budgets = DerivedTimeouts.budgets(environment)
        budgets.authentication.assert().isEqualTo(400.milliseconds)
        budgets.revocationRead.assert().isEqualTo(120.milliseconds)
        budgets.requiredListener.assert().isEqualTo(400.milliseconds)
        DerivedTimeouts.drain(environment).assert().isEqualTo(Duration.ofSeconds(10))
        DerivedTimeouts.controlRequiredListener(environment).assert().isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    fun `the startup line names each value and what it came from`() {
        val environment = MockEnvironment().withProperty("cosec.pipeline.timeout", "10s")
        DerivedTimeouts.line(environment, Duration.ofSeconds(4)).assert()
            .contains("authentication=${2.seconds} (20 % of the pipeline timeout ${10.seconds})")
            .contains("control required listener=PT10S (the pipeline timeout)")
            .contains("listener drain=PT5S (a 6th of spring.lifecycle.timeout-per-shutdown-phase PT30S)")
            .contains("snapshot local-max-age=PT12S (3 × the revalidate interval)")
        DerivedSnapshotSettings.line(RuntimeAuthorizationProperties(), Duration.ofSeconds(5), environment).assert()
            .startsWith("CoSec derived, not configured:")
            .contains("stage budgets authentication=")
    }
}
