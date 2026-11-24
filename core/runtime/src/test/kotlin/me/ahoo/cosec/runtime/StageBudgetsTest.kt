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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `PIP-33` and `OBS-10`: every stage's timeout is a share of the one deadline, and the shares keep their promises. */
class StageBudgetsTest {
    @Test
    fun `at the default deadline every share is the default it replaced`() {
        val budgets = StageBudgets.of(5.seconds)
        budgets.authentication.assert().isEqualTo(1.seconds)
        budgets.membership.assert().isEqualTo(500.milliseconds)
        budgets.attribute.assert().isEqualTo(300.milliseconds)
        budgets.rateLimit.assert().isEqualTo(500.milliseconds)
        budgets.revocationRead.assert().isEqualTo(300.milliseconds)
        budgets.requiredListener.assert().isEqualTo(1.seconds)
        StageBudgets.drain(30.seconds).assert().isEqualTo(5.seconds)
    }

    @Test
    fun `the pipeline's options default to the shares of their timeout`() {
        val options = PipelineOptions(timeout = 2.seconds)
        options.authenticationTimeout.assert().isEqualTo(400.milliseconds)
        options.membershipTimeout.assert().isEqualTo(200.milliseconds)
        options.attributeTimeout.assert().isEqualTo(120.milliseconds)
        options.rateLimitTimeout.assert().isEqualTo(200.milliseconds)
        PipelineSettingsProbe.optionsOf(2.seconds).authenticationTimeout.assert().isEqualTo(400.milliseconds)
    }

    /**
     * `PIP-7`: CoSec's revocation reader reads twice (the token, then its principal), each within its budget; both end
     * inside the authentication budget, so a Redis that hangs is an unavailable store, never a 503 of the authenticator.
     */
    @ParameterizedTest
    @ValueSource(longs = [20, 100, 1_000, 5_000, 30_000, 120_000])
    fun `a token's two revocation reads end inside the authentication budget`(millis: Long) {
        val budgets = StageBudgets.of(millis.milliseconds)
        (budgets.revocationRead * 2).assert().describedAs("$millis ms").isLessThan(budgets.authentication)
    }

    /** `OBS-10`: a REQUIRED listener's wait ends inside the request, so the deadline, not the listener, ends it. */
    @ParameterizedTest
    @ValueSource(longs = [5, 100, 1_000, 5_000, 120_000])
    fun `a required listener's wait ends inside the request`(millis: Long) {
        val budgets = StageBudgets.of(millis.milliseconds)
        budgets.requiredListener.assert().describedAs("$millis ms").isLessThan(budgets.timeout)
        StageBudgets.drain(millis.milliseconds).assert().isLessThan(millis.milliseconds)
    }

    @Test
    fun `a share of a tiny deadline is never zero`() {
        val budgets = StageBudgets.of(1.milliseconds)
        listOf(budgets.authentication, budgets.membership, budgets.attribute, budgets.rateLimit, budgets.revocationRead)
            .forEach { it.assert().isEqualTo(StageBudgets.FLOOR) }
        StageBudgets.drain(Duration.ZERO).assert().isEqualTo(StageBudgets.FLOOR)
    }

    @Test
    fun `the startup line names each share and what it is a share of`() {
        StageBudgets.of(5.seconds).toString().assert()
            .contains("authentication=1s (20 % of the pipeline timeout 5s)")
            .contains("revocation read=300ms (30 % of the authentication budget)")
            .contains("required listener=1s (20 %)")
            // CTL-85: how long a control call waits for the control plane's work budget.
            .contains("control work wait=2.5s (50 %)")
        StageBudgets.of(5.seconds).workWait.assert().isEqualTo(2_500.milliseconds)
    }

    private object PipelineSettingsProbe {
        fun optionsOf(timeout: Duration): PipelineOptions =
            PipelineSettings().apply { this.timeout = java.time.Duration.ofMillis(timeout.inWholeMilliseconds) }.toOptions()
    }
}
