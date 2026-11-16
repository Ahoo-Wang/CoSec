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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.control.change.ChangeJson.factory
import me.ahoo.cosec.control.change.ChangeJson.need
import me.ahoo.cosec.control.change.ChangeJson.only
import me.ahoo.cosec.control.change.ChangeJson.text
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/** A change's shadow window state, both ways. */
internal object ShadowStateCodec {
    internal fun shadowTree(shadow: ShadowState): ObjectNode {
        val node = factory.objectNode().put("window", shadow.window).put("startedAt", shadow.startedAt.toString())
        shadow.closed?.let { closed ->
            val summary = node.putObject("closed")
                .put("closedAt", closed.closedAt.toString())
                .put("insufficient", closed.insufficient)
                .put("blocked", closed.blocked)
            val counts = summary.putObject("counts")
            closed.counts.toSortedMap().forEach { (outcome, count) -> counts.put(outcome, count) }
        }
        if (shadow.reporters.isNotEmpty()) {
            node.putArray("reporters").apply { shadow.reporters.sorted().forEach { add(it) } }
        }
        return node
    }

    internal fun shadow(node: JsonNode): ShadowState {
        node.only("window", "startedAt", "closed", "reporters")
        return ShadowState(
            node.need("window").text(),
            Instant.parse(node.need("startedAt").text()),
            node.get("closed")?.let { closed ->
                closed.only("counts", "closedAt", "insufficient", "blocked")
                ShadowSummary(
                    closed.need("counts").properties().associate { (outcome, count) -> outcome to count.asLong() },
                    Instant.parse(closed.need("closedAt").text()),
                    closed.need("insufficient").asBoolean(),
                    closed.need("blocked").asBoolean(),
                )
            },
            node.get("reporters")?.values()?.mapTo(LinkedHashSet()) { it.text() }.orEmpty(),
        )
    }
}
