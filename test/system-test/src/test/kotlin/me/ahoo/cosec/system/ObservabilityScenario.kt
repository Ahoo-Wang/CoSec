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
import java.net.URI

/**
 * Scenario 10, observability as an operator sees it (OBS-1, OBS-5, STA-1): the health of a running process and that a
 * decision is visible in its metrics. A decision is counted once, by its result: `cosec.authorization` with the tag
 * `result` (`allow`, `explicit_deny`, ...), over the whole pipeline. Measured as a difference, so what earlier scenarios
 * decided does not matter, and exactly: the requests sent are the requests counted.
 *
 * At the enforcer, whose management port serves the metrics. The gateway image has no metrics endpoint yet (finding F1).
 */
@Tag("system")
@Order(90)
@ExtendWith(TranscriptExtension::class)
class ObservabilityScenario {
    private val management get() = SystemTopology.enforcerManagement

    /** The count of `cosec.authorization` with [result], 0 when no such decision has been counted yet. */
    private fun decisions(result: String): Long {
        val answer = Http.get(URI.create("$management/actuator/metrics/cosec.authorization?tag=result:$result"))
        if (answer.status == 404) return 0
        answer.status.assert().describedAs("the metrics endpoint: $answer").isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val measurements = answer.json().getValue("measurements") as List<Map<String, Any?>>
        return (measurements.single { it["statistic"] == "COUNT" }.getValue("value") as Number).toLong()
    }

    @Test
    fun `the process is healthy once its documents are installed, and its decisions are counted by result`() {
        val health = Http.get(management.resolve("/actuator/health"))
        health.status.assert().describedAs("health: $health").isEqualTo(200)
        health.json()["status"].assert().isEqualTo("UP")

        val allowedBefore = decisions("allow")
        val deniedBefore = decisions("explicit_deny")
        val caller = Token.issue("olga", "observability", app = "orders")
        repeat(
            3
        ) { Http.get(SystemTopology.enforcer.resolve("/orders/1"), caller.bearer).status.assert().isEqualTo(200) }
        repeat(
            2
        ) { Contract.assertProblem(Http.get(SystemTopology.enforcer.resolve("/denied/1"), caller.bearer), "forbidden") }

        eventually(
            Bounds.LISTENER_DELIVERY,
            "the decisions counted",
            { decisions("allow") to decisions("explicit_deny") }
        ) {
            it == (allowedBefore + 3 to deniedBefore + 2)
        }
    }
}
