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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttpSupport.get
import me.ahoo.cosec.control.api.ControlHttpSupport.json
import me.ahoo.cosec.control.api.ControlHttpSupport.post
import me.ahoo.cosec.control.api.ControlRequestJson.query
import me.ahoo.cosec.control.api.ControlRequestJson.simulated
import me.ahoo.cosec.control.api.ControlRequestJson.version
import me.ahoo.cosec.control.api.ControlResponseJson.explanation
import me.ahoo.cosec.control.api.ControlResponseJson.report
import me.ahoo.cosec.control.api.ControlResponseJson.simulation
import me.ahoo.cosec.control.api.JsonFields.field
import me.ahoo.cosec.control.api.JsonFields.need
import me.ahoo.cosec.control.api.JsonFields.optText
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId

/** The routes of one segment that ask the control plane about decisions (RFC 0007 §10.3): simulate, explain, analyze. */
internal class QueryRoutes(private val plane: ControlPlane) {
    suspend fun simulate(operator: Operator, request: ControlHttpRequest): ControlHttpResponse = post(
        request
    ) {
        json(
            simulation(
                plane.simulate(
                    operator,
                    simulated(it.need("request")),
                    it.optText("candidate")?.let { c -> field("candidate", "a change id") { ChangeId(c) } }
                )
            )
        )
    }

    suspend fun explain(operator: Operator, request: ControlHttpRequest): ControlHttpResponse = post(
        request
    ) {
        json(
            explanation(plane.explain(operator, simulated(it.need("request")), it.get("at")?.let(::version)))
        )
    }

    suspend fun analyze(operator: Operator, request: ControlHttpRequest): ControlHttpResponse = post(
        request
    ) { json(report(plane.analyze(operator, query(it.need("query"))))) }
}
