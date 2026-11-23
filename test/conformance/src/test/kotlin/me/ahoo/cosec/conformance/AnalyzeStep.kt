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

package me.ahoo.cosec.conformance

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.AccessEntry
import me.ahoo.cosec.control.api.AccessQuery
import me.ahoo.cosec.control.api.AccessReport
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode

/**
 * The `analyze` step of [ChangeRunner] (RFC 0007 §10.3, `CTL-51`):
 * `{ "analyze": { "by": "viv", "query": { "tenant": "acme", "app": "order", "space": "a", "action": "order:read",
 * "resource": "order/1" } } }`, answered `allows <bindings> denies <bindings> hidden <n>` (`none` for no binding).
 */
internal object AnalyzeStep {
    /** [argument]'s query, asked of [plane] by [operator]: its answer, or what [refused] writes of a refusal. */
    suspend fun run(
        plane: ControlPlane,
        operator: Operator,
        argument: JsonNode,
        refused: (ChangeRejectedException) -> String,
    ): String = try {
        argument.only("by", "query")
        answer(plane.analyze(operator, query(argument.required("query"))))
    } catch (error: ChangeRejectedException) {
        refused(error)
    }

    private fun query(node: JsonNode): AccessQuery {
        node.only("tenant", "app", "space", "action", "resource")
        val action = node.required("action").text().split(':', limit = 2)
        val resource = node.required("resource").text().split('/', limit = 2)
        return AccessQuery(
            TenantId(node.required("tenant").text()),
            node.get("app")?.text(),
            node.get("space")?.text(),
            Action(action[0], action[1]),
            Resource(resource[0], resource.getOrElse(1) { "" }, emptyMap()),
        )
    }

    private fun answer(report: AccessReport): String =
        "allows ${ids(report.allows)} denies ${ids(report.denies)} hidden ${report.hidden}"

    private fun ids(entries: List<AccessEntry>) = entries.joinToString(",") { it.binding }.ifEmpty { "none" }
}
