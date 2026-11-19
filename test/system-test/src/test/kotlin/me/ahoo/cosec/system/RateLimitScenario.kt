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

package me.ahoo.cosec.system

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Scenario 5, a rate limit shared by the replicas (OBL-1, OBL-4, RTL-1): one bucket per signed-in principal in Redis,
 * counted by both gateways. The rule allows exactly 5 permits per 81920 s window: 2^-14 permits per second, which no
 * floating-point rounding can move, over 5 * 2^14 seconds, the longest such window the one-day maximum of
 * `RateLimitSpec.MAX_WINDOW_SECONDS` allows. A window that rolled over during
 * the scenario would refuse it: it takes about 1 s, so that is one run in 82,000 (a quota of 5 cannot be exact over a longer
 * period), and it would be a finding to report, never a retry.
 */
@Tag("system")
@Order(50)
@ExtendWith(TranscriptExtension::class)
class RateLimitScenario {
    @Test
    fun `a denied request spends no permit, and the sixth permitted one is a 429 with its headers, whichever replica`() {
        Control(SignIn.root()).seed("tenant:rate", "tenants/rate.json")
        val caller = SignIn.of("rita", "rate", app = "orders")
        // The tenant's document reaches each replica on its own: wait for both with a principal whose bucket is not rita's,
        // so what the count below starts from is untouched.
        val warm = SignIn.of("rita-warm", "rate", app = "orders")
        for (gateway in SystemTopology.gateways) {
            eventually(Bounds.SNAPSHOT_PROPAGATION, "the rate limit rule counting at $gateway", {
                Http.get(gateway.resolve("/behind/limited/1"), warm.bearer)
            }) { it.header("RateLimit-Limit") != null }
        }
        val first = { Http.get(SystemTopology.gateway.resolve("/behind/limited/1"), caller.bearer) }
        // The tenant's document is installed once its rule counts: the first permitted request carries the headers.
        eventually(Bounds.SNAPSHOT_PROPAGATION, "the rate limit rule counting", first) {
            it.header("RateLimit-Limit") != null
        }.let { counted ->
            counted.header("RateLimit-Limit").assert().isEqualTo("5")
            counted.header("RateLimit-Remaining").assert().isEqualTo("4")
        }

        // Denied requests: the rule matches their path, but a refused request spends nothing (OBL-1).
        repeat(3) {
            for (gateway in SystemTopology.gateways) {
                Contract.assertProblem(Http.get(gateway.resolve("/behind/denied/1"), caller.bearer), "forbidden")
            }
        }

        // Four more permits, alternating the replicas: one bucket, so each answer counts down from the other's.
        val remaining = (1..4).map { index ->
            val gateway = SystemTopology.gateways[index % 2]
            val answer = Http.get(gateway.resolve("/behind/limited/1"), caller.bearer)
            answer.status.assert().describedAs("permit ${index + 1} of 5 at $gateway: $answer").isEqualTo(200)
            answer.header("RateLimit-Remaining")
        }
        remaining.assert().isEqualTo(listOf("3", "2", "1", "0"))

        for (gateway in SystemTopology.gateways) {
            val refused = Http.get(gateway.resolve("/behind/limited/1"), caller.bearer)
            Contract.assertProblem(refused, "too-many-requests")
            refused.header("Retry-After").assert().isNotNull()
            refused.header("RateLimit-Limit").assert().isEqualTo("5")
            refused.header("RateLimit-Remaining").assert().isEqualTo("0")
            refused.header("RateLimit-Reset").assert().isNotNull()
        }
        // Another principal of the tenant has a bucket of its own.
        Http.get(SystemTopology.gateway.resolve("/behind/limited/1"), SignIn.of("ron", "rate", app = "orders").bearer)
            .status.assert().isEqualTo(200)
    }
}
