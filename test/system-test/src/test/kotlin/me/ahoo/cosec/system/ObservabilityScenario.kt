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
 * At the enforcer (the metrics endpoint of its management port) and at the gateway (the Prometheus scrape endpoint of the
 * image's management port, `cosec_authorization_seconds_count`), and in the audit trail: the gateways audit to Kafka, and the
 * record of a request is on the topic, keyed by its tenant and principal, with the request's id.
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

    /** The sum of the gateway's `cosec_authorization_seconds_count` series whose `result` is [result], from the scrape endpoint. */
    private fun gatewayDecisions(result: String): Long {
        val scrape = Http.get(SystemTopology.gatewayManagement.resolve("/actuator/prometheus"))
        scrape.status.assert().describedAs("the scrape endpoint: ${scrape.status}").isEqualTo(200)
        return scrape.body.lineSequence()
            .filter { it.startsWith("cosec_authorization_seconds_count{") && it.contains("result=\"$result\"") }
            .sumOf { it.substringAfterLast(' ').toDouble().toLong() }
    }

    /** The records of the audit topic, `key<TAB>value` each, as a console consumer reads them from the start. */
    private fun auditRecords(): List<String> = Compose.execAnyway(
        "kafka",
        "/opt/kafka/bin/kafka-console-consumer.sh",
        "--bootstrap-server",
        "kafka:9092",
        "--topic",
        "cosec-audit",
        "--from-beginning",
        "--timeout-ms",
        "4000",
        "--property",
        "print.key=true",
    ).lines()

    @Test
    fun `the gateway counts its decisions by result, and each is on the audit topic with its request id`() {
        val allowedBefore = gatewayDecisions("allow")
        val deniedBefore = gatewayDecisions("explicit_deny")
        val caller = Token.issue("kay", "audit-tenant", app = "orders")
        val path = "/behind/orders/audit-${java.util.UUID.randomUUID()}"
        val permitted = Http.get(SystemTopology.gateway.resolve(path), caller.bearer)
        permitted.status.assert().isEqualTo(200)
        repeat(
            2
        ) { Http.get(SystemTopology.gateway.resolve("/behind/orders/1"), caller.bearer).status.assert().isEqualTo(200) }
        repeat(
            2
        ) {
            Contract.assertProblem(
                Http.get(SystemTopology.gateway.resolve("/behind/denied/1"), caller.bearer),
                "forbidden"
            )
        }

        eventually(
            Bounds.LISTENER_DELIVERY,
            "the gateway's decisions counted",
            { gatewayDecisions("allow") to gatewayDecisions("explicit_deny") }
        ) {
            it == (allowedBefore + 3 to deniedBefore + 2)
        }
        val record = eventually(
            Bounds.AUDIT_DELIVERY,
            "the request on the audit topic",
            { auditRecords().firstOrNull { line -> line.contains(path) } }
        ) {
            it != null
        }!!
        Transcript.note("audit record: $record")
        record.substringBefore('\t').assert().isEqualTo("[\"audit-tenant\",\"kay\"]")
        record.assert().contains(permitted.header("CoSec-Request-Id")!!).contains("ALLOW")
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
